// This file is the entrypoint to the native program. It is based on the example code shipped with Avian.
//
// It is not compiled by the build system, instead we ship pre-compiled object files for each platform in order
// to simplify deployment and usage.
//
// The name of the main class is substituted on the fly via direct binary editing.
//
// It should be compiled like this:
//
// g++ -I$JAVA_HOME/include -I$JAVA_HOME/include/linux -I$JAVA_HOME/include/darwin -c bootstrap.cpp -o ../resources/net/plan99/gumball/bootstrap-mac64.o

#define D_JNI_IMPLEMENTATION_
#include "stdint.h"
#include "jni.h"
#include "stdlib.h"

#if (defined __MINGW32__) || (defined _MSC_VER)
#  define EXPORT __declspec(dllexport)
#else
#  define EXPORT __attribute__ ((visibility("default"))) \
  __attribute__ ((used))
#endif

#if (! defined __x86_64__) && ((defined __MINGW32__) || (defined _MSC_VER))
#  define SYMBOL(x) binary_boot_jar_##x
#else
#  define SYMBOL(x) _binary_boot_jar_##x
#endif

extern "C" {

  extern const uint8_t SYMBOL(start)[];
  extern const uint8_t SYMBOL(end)[];

  EXPORT const uint8_t*
  bootJar(size_t* size)
  {
    *size = SYMBOL(end) - SYMBOL(start);
    return SYMBOL(start);
  }

} // extern "C"

extern "C" void __cxa_pure_virtual(void) { abort(); }

const char *injected_data = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

int main(int ac, const char** av)
{
  JavaVMInitArgs vmArgs;
  vmArgs.version = JNI_VERSION_1_2;
  vmArgs.nOptions = 4;
  vmArgs.ignoreUnrecognized = JNI_TRUE;

  JavaVMOption options[vmArgs.nOptions];
  vmArgs.options = options;

  // TODO: Make the JVM options pre-calculated.
  if (injected_data[0] == 'L')
    options[0].optionString = const_cast<char*>("-Xbootclasspath:[lzma.bootJar]");
  else
    options[0].optionString = const_cast<char*>("-Xbootclasspath:[bootJar]");

  // Force the user country to USA for now. Non en_US locales require big locale tables which bloat the binary.
  // In future we may add a flag to create larger binaries if you want i18n data tables. It's probably OK to
  // force en_US though, because many command line tools won't care about internationalisation.
  options[1].optionString = const_cast<char*>("-Duser.country=US");

  // 1gb max heap size, 4mb stack size.
  options[2].optionString = const_cast<char*>("-Xmx1024M");
  options[3].optionString = const_cast<char*>("-Xss4M");

  JavaVM* vm;
  void* env;
  JNI_CreateJavaVM(&vm, &env, &vmArgs);
  JNIEnv* e = static_cast<JNIEnv*>(env);

  const char *classname = &injected_data[1];
  jclass c = e->FindClass(classname);
  if (not e->ExceptionCheck()) {
    jmethodID m = e->GetStaticMethodID(c, "main", "([Ljava/lang/String;)V");
    if (not e->ExceptionCheck()) {
      jclass stringClass = e->FindClass("java/lang/String");
      if (not e->ExceptionCheck()) {
        jobjectArray a = e->NewObjectArray(ac-1, stringClass, 0);
        if (not e->ExceptionCheck()) {
          for (int i = 1; i < ac; ++i) {
            e->SetObjectArrayElement(a, i-1, e->NewStringUTF(av[i]));
          }

          e->CallStaticVoidMethod(c, m, a);
        }
      }
    }
  }

  int exitCode = 0;
  if (e->ExceptionCheck()) {
    exitCode = -1;
    e->ExceptionDescribe();
  }

  vm->DestroyJavaVM();

  return exitCode;
}