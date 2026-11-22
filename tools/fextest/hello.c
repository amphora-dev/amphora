/*
 * x86 smoke test for FEX under Wine (M2, 12 §2.3). Build twice:
 *   x86_64-w64-mingw32-clang -O2 -o hello64.exe hello.c -luser32
 *   i686-w64-mingw32-clang   -O2 -o hello32.exe hello.c -luser32
 * Prints what Windows reports about the process and checks the things a
 * translator gets wrong first: CPUID, SSE math, threads with atomics, and an
 * exception raised and resumed through the dispatcher. Exit code 0 = all passed.
 * Ends with a message box carrying the same report (a GDI window drawn by the
 * translated user32 in the 32-bit build); `-q` skips it for headless runs.
 * `--log <file>` also writes the report there: a session started by the app
 * has no stdout (tools/device/run.sh reads it back).
 */
#include <windows.h>
#include <intrin.h>
#include <stdio.h>
#include <string.h>

static int failures;
static char report[4096];

static void check(const char *what, int ok, const char *detail)
{
    size_t used = strlen(report);
    printf("%s %-10s %s\n", ok ? "ok  " : "FAIL", what, detail);
    snprintf(report + used, sizeof(report) - used, "%s  %s: %s\n", ok ? "ok" : "FAIL", what, detail);
    if (!ok) failures++;
}

static const char *machine(USHORT m)
{
    switch (m) {
    case IMAGE_FILE_MACHINE_UNKNOWN: return "none";
    case IMAGE_FILE_MACHINE_I386: return "i386";
    case IMAGE_FILE_MACHINE_AMD64: return "x86_64";
    case IMAGE_FILE_MACHINE_ARM64: return "arm64";
    default: return "other";
    }
}

static volatile LONG counter;

static DWORD WINAPI worker(void *arg)
{
    (void)arg;
    for (int i = 0; i < 100000; i++) InterlockedIncrement(&counter);
    return 0;
}

static int resumed;

static LONG CALLBACK handler(EXCEPTION_POINTERS *info)
{
    if (info->ExceptionRecord->ExceptionCode != 0xE0000001) return EXCEPTION_CONTINUE_SEARCH;
    resumed++;
    return EXCEPTION_CONTINUE_EXECUTION;
}

int main(int argc, char **argv)
{
    char buf[MAX_PATH + 64];

    printf("hello from %d-bit x86\n", (int)sizeof(void *) * 8);

    USHORT process = 0, native = 0;
    BOOL (WINAPI *wow64)(HANDLE, USHORT *, USHORT *) = (void *)GetProcAddress(
        GetModuleHandleA("kernel32.dll"), "IsWow64Process2");
    if (wow64 && wow64(GetCurrentProcess(), &process, &native)) {
        snprintf(buf, sizeof(buf), "process %s, native %s", machine(process), machine(native));
        check("machine", native == IMAGE_FILE_MACHINE_ARM64, buf);
    } else {
        check("machine", 0, "IsWow64Process2 unavailable");
    }

    GetSystemDirectoryA(buf, MAX_PATH);
    check("sysdir", 1, buf);

    int regs[4];
    char vendor[13];
    __cpuid(regs, 0);
    memcpy(vendor, &regs[1], 4);
    memcpy(vendor + 4, &regs[3], 4);
    memcpy(vendor + 8, &regs[2], 4);
    vendor[12] = 0;
    __cpuid(regs, 1);
    snprintf(buf, sizeof(buf), "%s, sse2 %s", vendor, (regs[3] >> 26) & 1 ? "yes" : "no");
    check("cpuid", (regs[3] >> 26) & 1, buf);

    /* Deterministic double math: same value on any IEEE double machine. */
    volatile double x = 0;
    for (int i = 1; i <= 1000000; i++) x += 1.0 / ((double)i * i);
    snprintf(buf, sizeof(buf), "sum 1/n^2 = %.12f", x);
    check("math", x > 1.644933 && x < 1.644935, buf);

    HANDLE threads[4];
    for (int i = 0; i < 4; i++) threads[i] = CreateThread(NULL, 0, worker, NULL, 0, NULL);
    WaitForMultipleObjects(4, threads, TRUE, INFINITE);
    snprintf(buf, sizeof(buf), "4 threads, counter %ld", counter);
    check("threads", counter == 400000, buf);

    AddVectoredExceptionHandler(1, handler);
    RaiseException(0xE0000001, 0, 0, NULL);
    snprintf(buf, sizeof(buf), "raised and resumed %d", resumed);
    check("exception", resumed == 1, buf);

    printf("%s\n", failures ? "FAILED" : "PASSED");
    int quiet = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-q")) quiet = 1;
        else if (!strcmp(argv[i], "--log") && i + 1 < argc) {
            FILE *f = fopen(argv[++i], "w");
            if (f) {
                fprintf(f, "hello from %d-bit x86\n%s%s\n", (int)sizeof(void *) * 8, report, failures ? "FAILED" : "PASSED");
                fclose(f);
            }
        }
    }
    if (!quiet) {
        char title[64];
        snprintf(title, sizeof(title), "x86 %d-bit: %s", (int)sizeof(void *) * 8, failures ? "FAILED" : "PASSED");
        MessageBoxA(NULL, report, title, failures ? MB_ICONERROR : MB_ICONINFORMATION);
    }
    return failures ? 1 : 0;
}
