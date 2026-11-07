/* Which syscalls Wine wants does the Android app seccomp filter allow?
 * Run inside the app: see app WineActivity "amphora.exec". A blocked syscall
 * kills the process with SIGSYS, so each probe runs in a forked child. */
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void probe(const char *name, long nr, long a0, long a1, long a2, long a3, long a4)
{
    pid_t pid = fork();
    if (pid == 0)
    {
        long ret = syscall(nr, a0, a1, a2, a3, a4);
        printf("%-16s nr=%-4ld ret=%ld errno=%d (%s)\n", name, nr, ret, ret < 0 ? errno : 0,
               ret < 0 ? strerror(errno) : "ok");
        fflush(stdout);
        _exit(0);
    }
    int status;
    waitpid(pid, &status, 0);
    if (WIFSIGNALED(status))
        printf("%-16s nr=%-4ld KILLED by signal %d%s\n", name, nr, WTERMSIG(status),
               WTERMSIG(status) == SIGSYS ? " (seccomp)" : "");
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);  /* forked children must not inherit buffered lines */
    probe("futex_waitv", 449, 0, 0, 0, 0, 0);         /* fsync */
    probe("userfaultfd", 282, 0x80801, 0, 0, 0, 0);   /* write watches (O_CLOEXEC|O_NONBLOCK|USER_MODE_ONLY) */
    probe("memfd_create", 279, (long)"probe", 1, 0, 0, 0);
    probe("pidfd_open", 434, getpid(), 0, 0, 0, 0);
    probe("clone3", 435, 0, 0, 0, 0, 0);
    probe("process_vm_readv", 270, getpid(), 0, 0, 0, 0);
    probe("set_robust_list", 99, 0, 0, 0, 0, 0);
    return 0;
}
