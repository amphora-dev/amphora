/* Thin-prefix copy-on-write test (docs/research/12 §3.3).
 * Run in a prefix where C:\cowtest\*.txt are symlinks into a shared store.
 * Each case touches one link; the shell side then checks that the store file
 * is untouched and the prefix entry became a real file (or vanished). */
#include <windows.h>
#include <stdio.h>

static void report(const char *name, BOOL ok)
{
    printf("%-22s %s (err=%lu)\n", name, ok ? "ok" : "FAILED", ok ? 0 : GetLastError());
}

static BOOL write_new(const WCHAR *path, DWORD disposition)
{
    DWORD done;
    HANDLE h = CreateFileW(path, GENERIC_WRITE, 0, NULL, disposition, FILE_ATTRIBUTE_NORMAL, NULL);
    BOOL ok;
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    ok = WriteFile(h, "PREFIX\n", 7, &done, NULL);
    CloseHandle(h);
    return ok;
}

static BOOL map_write(const WCHAR *path)
{
    HANDLE f = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL), m;
    char *p;
    if (f == INVALID_HANDLE_VALUE) return FALSE;
    m = CreateFileMappingW(f, NULL, PAGE_READWRITE, 0, 0, NULL);
    CloseHandle(f);
    if (!m) return FALSE;
    p = MapViewOfFile(m, FILE_MAP_WRITE, 0, 0, 0);
    CloseHandle(m);
    if (!p) return FALSE;
    memcpy(p, "MAPPED", 6);
    UnmapViewOfFile(p);
    return TRUE;
}

static BOOL read_back(const WCHAR *path)
{
    char buf[16] = {0};
    DWORD done;
    HANDLE h = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    BOOL ok;
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    ok = ReadFile(h, buf, sizeof(buf) - 1, &done, NULL);
    CloseHandle(h);
    printf("  read: %s", buf);
    return ok;
}

int main(void)
{
    report("read",             read_back(L"C:\\cowtest\\read.txt"));
    report("DeleteFile",       DeleteFileW(L"C:\\cowtest\\delete.txt"));
    report("MoveFileEx",       MoveFileExW(L"C:\\cowtest\\move.txt", L"C:\\cowtest\\moved.txt", 0));
    report("CREATE_ALWAYS",    write_new(L"C:\\cowtest\\create_always.txt", CREATE_ALWAYS));
    report("TRUNCATE_EXISTING",write_new(L"C:\\cowtest\\truncate.txt", TRUNCATE_EXISTING));
    report("OPEN_EXISTING+write", write_new(L"C:\\cowtest\\overwrite.txt", OPEN_EXISTING));
    report("SetFileAttributes",SetFileAttributesW(L"C:\\cowtest\\attrib.txt", FILE_ATTRIBUTE_HIDDEN));
    report("map write",        map_write(L"C:\\cowtest\\map.txt"));
    return 0;
}
