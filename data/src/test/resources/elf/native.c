typedef void* JavaVM; typedef void* JNIEnv; typedef void* jobject; typedef int jint;
extern int dep_value(void);
__attribute__((visibility("default"))) jint Java_com_example_demo_NativeBridge_add(JNIEnv* e, jobject o, jint a, jint b) { return a + b + dep_value(); }
__attribute__((visibility("default"))) jint Java_com_example_demo_Native_1Util_version__I(JNIEnv* e, jobject o, jint x) { return x; }
__attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM* vm, void* reserved) { return 0x00010006; }
static int internal_helper(int x) { return x * 2; }
int other_exported(int x) { return internal_helper(x); }
