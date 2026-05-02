/*
 * jni_md.h for win32 — vendored so the openh264-jni Makefile can
 * cross-compile from a Linux runner whose JDK ships only
 * include/linux/jni_md.h. Content matches the canonical OpenJDK
 * win32 header. JNIEXPORT/JNIIMPORT use __declspec for proper PE
 * import/export tables; JNICALL is __stdcall on x86 (a no-op on
 * x86_64, where the Windows ABI is unified).
 *
 * `__int64` is an MSVC extension that mingw-w64 also supports, so
 * this header works under either toolchain.
 */

#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL   __stdcall

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif /* !_JAVASOFT_JNI_MD_H_ */
