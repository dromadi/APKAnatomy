/* Fixture for native import and string detection: calls functions it does not define, so they stay
   undefined (imported) in .dynsym, and keeps a URL in .rodata. Built with -nostdlib, never run. */
extern long ptrace(int request, int pid, void* addr, void* data);
extern void* dlopen(const char* name, int flags);
extern int execve(const char* path, char* const argv[], char* const envp[]);
extern int __system_property_get(const char* name, char* value);
extern void* SSL_CTX_new(const void* method);
extern int printf(const char* format, ...);
static const char endpoint[] = "https://native.contoh.id/v1/telemetry";
__attribute__((visibility("default"))) int checks(char* buffer) {
    printf("%s", endpoint);
    __system_property_get("ro.build.tags", buffer);
    SSL_CTX_new(0);
    return (int) ptrace(0, 0, 0, 0) + (dlopen("libpayload.so", 0) != 0) + execve("/system/bin/sh", 0, 0);
}
