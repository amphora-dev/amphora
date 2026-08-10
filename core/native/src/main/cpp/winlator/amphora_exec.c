/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Android app-data exec interceptor for Amphora.
 *
 * Derived from termux-play-store/termux-exec:
 * https://github.com/termux-play-store/termux-exec
 *
 * Unlike Termux, Amphora discovers its private root at runtime through
 * AMPHORA_EXEC_ROOT and rewrites /bin or /usr/bin shebangs through PREFIX.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <paths.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#if UINTPTR_MAX == UINT64_MAX
#define SYSTEM_LINKER_PATH "/system/bin/linker64"
#else
#define SYSTEM_LINKER_PATH "/system/bin/linker"
#endif

#ifdef __aarch64__
#define EM_NATIVE EM_AARCH64
#elif defined(__arm__)
#define EM_NATIVE EM_ARM
#elif defined(__x86_64__)
#define EM_NATIVE EM_X86_64
#elif defined(__i386__)
#define EM_NATIVE EM_386
#else
#error "Unsupported architecture"
#endif

#define EXEC_ROOT_ENV "AMPHORA_EXEC_ROOT"
#define SELF_EXE_ENV "AMPHORA_EXEC__PROC_SELF_EXE"
#define OPT_OUT_ENV "AMPHORA_EXEC_OPTOUT"
#define DEBUG_ENV "AMPHORA_EXEC_DEBUG"
#define LOG_PREFIX "[amphora-exec] "

extern char **environ;

struct file_header_info {
  bool is_elf;
  bool is_native_elf;
  char interpreter_buffer[PATH_MAX];
  const char *interpreter;
  const char *interpreter_arg;
};

static bool starts_with_path(const char *path, const char *root) {
  if (path == NULL || root == NULL || root[0] == '\0') return false;
  size_t root_length = strlen(root);
  if (strncmp(path, root, root_length) != 0) return false;
  return path[root_length] == '\0' || path[root_length] == '/';
}

static int raw_execve(const char *path, char *const argv[], char *const envp[]) {
  return syscall(SYS_execve, path, argv, envp);
}

static const char *normalize_path(const char *path, char *buffer) {
  if (path == NULL || path[0] == '/') return path;

  char cwd[PATH_MAX];
  if (getcwd(cwd, sizeof(cwd)) == NULL) return path;
  if (snprintf(buffer, PATH_MAX, "%s/%s", cwd, path) >= PATH_MAX) return path;
  return buffer;
}

static const char *rewrite_interpreter(const char *path, char *buffer) {
  const char *suffix = NULL;
  if (strncmp(path, "/bin/", 5) == 0) {
    suffix = path + 5;
  } else if (strncmp(path, "/usr/bin/", 9) == 0) {
    suffix = path + 9;
  }
  if (suffix == NULL) return path;

  const char *prefix = getenv("PREFIX");
  if (prefix == NULL || prefix[0] == '\0') return path;
  if (snprintf(buffer, PATH_MAX, "%s/bin/%s", prefix, suffix) >= PATH_MAX) return path;
  return buffer;
}

static void inspect_file_header(char *header, size_t length, struct file_header_info *result) {
  if (length >= sizeof(Elf32_Ehdr) && memcmp(header, ELFMAG, SELFMAG) == 0) {
    const Elf32_Ehdr *elf_header = (const Elf32_Ehdr *)header;
    result->is_elf = true;
    result->is_native_elf = elf_header->e_machine == EM_NATIVE;
    return;
  }

  if (length < 4 || header[0] != '#' || header[1] != '!') return;

  char *newline = memchr(header, '\n', length);
  if (newline == NULL) return;
  while (newline > header + 2 && (newline[-1] == ' ' || newline[-1] == '\t')) newline--;
  *newline = '\0';

  char *interpreter = header + 2;
  while (*interpreter == ' ' || *interpreter == '\t') interpreter++;
  if (*interpreter == '\0') return;

  char *argument = interpreter;
  while (*argument != '\0' && *argument != ' ' && *argument != '\t') argument++;
  if (*argument != '\0') {
    *argument++ = '\0';
    while (*argument == ' ' || *argument == '\t') argument++;
    if (*argument != '\0') result->interpreter_arg = argument;
  }

  result->interpreter =
      rewrite_interpreter(
          interpreter, result->interpreter_buffer);
}

static char **copy_environment(
    char *const envp[], const char *self_executable, bool preserve_runtime, char **self_entry) {
  size_t count = 0;
  while (envp[count] != NULL) count++;

  char **copy = calloc(count + 2, sizeof(char *));
  if (copy == NULL) return NULL;

  if (self_executable != NULL
      && asprintf(self_entry, SELF_EXE_ENV "=%s", self_executable) < 0) {
    free(copy);
    return NULL;
  }

  size_t output = 0;
  bool replaced_self = false;
  for (size_t input = 0; input < count; input++) {
    const char *entry = envp[input];
    if (strncmp(entry, SELF_EXE_ENV "=", sizeof(SELF_EXE_ENV)) == 0) {
      if (self_executable != NULL) {
        copy[output++] = *self_entry;
        replaced_self = true;
      }
      continue;
    }
    if (!preserve_runtime
        && (strncmp(entry, "LD_PRELOAD=", sizeof("LD_PRELOAD=") - 1) == 0
            || strncmp(entry, "LD_LIBRARY_PATH=", sizeof("LD_LIBRARY_PATH=") - 1) == 0)) {
      continue;
    }
    copy[output++] = (char *)entry;
  }
  if (self_executable != NULL && !replaced_self) copy[output++] = *self_entry;
  copy[output] = NULL;
  return copy;
}

static bool inspect_executable(const char *path, struct file_header_info *info) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;

  char header[256] = {0};
  ssize_t bytes_read = read(fd, header, sizeof(header) - 1);
  int saved_errno = errno;
  close(fd);
  errno = saved_errno;
  if (bytes_read <= 0) return false;

  inspect_file_header(header, (size_t)bytes_read, info);
  return info->is_elf || info->interpreter != NULL;
}

__attribute__((visibility("default"))) int execve(
    const char *executable_path, char *const argv[], char *const envp[]) {
  if (getenv(OPT_OUT_ENV) != NULL) return raw_execve(executable_path, argv, envp);
  if (executable_path == NULL || argv == NULL || envp == NULL) {
    errno = EFAULT;
    return -1;
  }

  const bool debug = getenv(DEBUG_ENV) != NULL;
  const char *original_path = executable_path;
  char normalized_path[PATH_MAX];
  executable_path = normalize_path(executable_path, normalized_path);

  struct file_header_info info = {0};
  if (!inspect_executable(executable_path, &info)) {
    return raw_execve(executable_path, argv, envp);
  }

  if (info.is_elf && !info.is_native_elf) {
    return raw_execve(executable_path, argv, envp);
  }

  if (info.interpreter != NULL) executable_path = info.interpreter;

  char resolved_path[PATH_MAX];
  const char *path_for_policy = realpath(executable_path, resolved_path);
  if (path_for_policy == NULL) path_for_policy = executable_path;

  const char *exec_root = getenv(EXEC_ROOT_ENV);
  bool wrap_in_linker =
      starts_with_path(path_for_policy, exec_root)
          || strcmp(path_for_policy, "/system/bin/sh") == 0;

  char **allocated_env = NULL;
  char *self_entry = NULL;
  allocated_env =
      copy_environment(
          envp, wrap_in_linker ? original_path : NULL, wrap_in_linker, &self_entry);
  if (allocated_env == NULL) {
    errno = ENOMEM;
    return -1;
  }
  envp = allocated_env;

  size_t argc = 0;
  while (argv[argc] != NULL) argc++;
  char **allocated_argv = NULL;

  if (wrap_in_linker || info.interpreter != NULL) {
    allocated_argv = calloc(argc + 4, sizeof(char *));
    if (allocated_argv == NULL) {
      free(self_entry);
      free(allocated_env);
      errno = ENOMEM;
      return -1;
    }

    size_t output = 0;
    allocated_argv[output++] = argv[0];
    if (wrap_in_linker) {
      allocated_argv[output++] = (char *)executable_path;
      executable_path = SYSTEM_LINKER_PATH;
    }
    if (info.interpreter != NULL) {
      if (info.interpreter_arg != NULL) {
        allocated_argv[output++] = (char *)info.interpreter_arg;
      }
      allocated_argv[output++] = (char *)original_path;
    }
    for (size_t input = 1; input < argc; input++) allocated_argv[output++] = argv[input];
    allocated_argv[output] = NULL;
    argv = allocated_argv;
  }

  if (debug) {
    fprintf(stderr, LOG_PREFIX "execve('%s') via '%s'\n", original_path, executable_path);
  }

  int result = raw_execve(executable_path, argv, envp);
  int saved_errno = errno;
  free(allocated_argv);
  free(self_entry);
  free(allocated_env);
  errno = saved_errno;
  return result;
}

__attribute__((visibility("default"))) int execv(const char *path, char *const argv[]) {
  return execve(path, argv, environ);
}

static int exec_as_script(const char *path, char *const argv[], char *const envp[]) {
  size_t argc = 0;
  while (argv[argc] != NULL) argc++;
  char *script_argv[argc + 2];
  script_argv[0] = "sh";
  script_argv[1] = (char *)path;
  memcpy(script_argv + 2, argv + 1, argc * sizeof(char *));
  return execve(_PATH_BSHELL, script_argv, envp);
}

__attribute__((visibility("default"))) int execvpe(
    const char *name, char *const argv[], char *const envp[]) {
  if (name == NULL || name[0] == '\0') {
    errno = ENOENT;
    return -1;
  }
  if (strchr(name, '/') != NULL) {
    execve(name, argv, envp);
    if (errno == ENOEXEC) return exec_as_script(name, argv, envp);
    return -1;
  }

  const char *path = getenv("PATH");
  if (path == NULL) path = _PATH_DEFPATH;
  char *path_copy = strdup(path);
  if (path_copy == NULL) {
    errno = ENOMEM;
    return -1;
  }

  bool saw_access_denied = false;
  char *cursor = path_copy;
  char *directory;
  while ((directory = strsep(&cursor, ":")) != NULL) {
    if (directory[0] == '\0') directory = ".";
    char candidate[PATH_MAX];
    if (snprintf(candidate, sizeof(candidate), "%s/%s", directory, name)
        >= (int)sizeof(candidate)) {
      continue;
    }
    execve(candidate, argv, envp);
    if (errno == ENOEXEC) {
      free(path_copy);
      return exec_as_script(candidate, argv, envp);
    }
    if (errno == EACCES) saw_access_denied = true;
    if (errno != EACCES && errno != ENOENT && errno != ENOTDIR && errno != ELOOP) {
      free(path_copy);
      return -1;
    }
  }
  free(path_copy);
  if (saw_access_denied) errno = EACCES;
  return -1;
}

__attribute__((visibility("default"))) int execvp(const char *name, char *const argv[]) {
  return execvpe(name, argv, environ);
}

enum exec_list_variant { EXEC_LIST, EXEC_LIST_ENV, EXEC_LIST_PATH };

static int exec_list(
    enum exec_list_variant variant, const char *name, const char *argv0, va_list arguments) {
  va_list count_arguments;
  va_copy(count_arguments, arguments);
  size_t argc = 1;
  while (va_arg(count_arguments, char *) != NULL) argc++;
  va_end(count_arguments);

  char *argv[argc + 1];
  argv[0] = (char *)argv0;
  for (size_t index = 1; index <= argc; index++) {
    argv[index] = va_arg(arguments, char *);
  }
  char **envp = variant == EXEC_LIST_ENV ? va_arg(arguments, char **) : environ;
  return variant == EXEC_LIST_PATH ? execvp(name, argv) : execve(name, argv, envp);
}

__attribute__((visibility("default"))) int execl(const char *name, const char *argv0, ...) {
  va_list arguments;
  va_start(arguments, argv0);
  int result = exec_list(EXEC_LIST, name, argv0, arguments);
  va_end(arguments);
  return result;
}

__attribute__((visibility("default"))) int execle(const char *name, const char *argv0, ...) {
  va_list arguments;
  va_start(arguments, argv0);
  int result = exec_list(EXEC_LIST_ENV, name, argv0, arguments);
  va_end(arguments);
  return result;
}

__attribute__((visibility("default"))) int execlp(const char *name, const char *argv0, ...) {
  va_list arguments;
  va_start(arguments, argv0);
  int result = exec_list(EXEC_LIST_PATH, name, argv0, arguments);
  va_end(arguments);
  return result;
}

__attribute__((visibility("default"))) int fexecve(
    int fd, char *const argv[], char *const envp[]) {
  char path[40];
  snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
  execve(path, argv, envp);
  if (errno == ENOENT) errno = EBADF;
  return -1;
}

__attribute__((visibility("default"))) ssize_t readlink(
    const char *restrict path, char *restrict buffer, size_t size) {
  if (strcmp(path, "/proc/self/exe") == 0) {
    const char *self_executable = getenv(SELF_EXE_ENV);
    if (self_executable != NULL) {
      size_t length = strlen(self_executable);
      size_t copy_length = length < size ? length : size;
      memcpy(buffer, self_executable, copy_length);
      return (ssize_t)copy_length;
    }
  }
  return syscall(SYS_readlinkat, AT_FDCWD, path, buffer, size);
}

__attribute__((visibility("default"))) char *realpath(
    const char *path, char *resolved_path) {
  char *(*original_realpath)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
  if (strcmp(path, "/proc/self/exe") == 0) {
    const char *self_executable = getenv(SELF_EXE_ENV);
    if (self_executable != NULL) path = self_executable;
  }
  return original_realpath(path, resolved_path);
}
