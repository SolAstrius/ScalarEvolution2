/*
 * jni_md.h for linux — vendored so the mlterm-jni Makefile can
 * cross-compile to Linux from a non-Linux host (e.g. a macOS dev box
 * whose JDK ships only include/darwin/jni_md.h). Content is the
 * canonical OpenJDK linux header: a thin ELF visibility shim around
 * libc integer primitives. On a Linux build host the JDK's own
 * include/linux/jni_md.h is identical, so CI passes that instead.
 */

#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT     __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL

typedef int jint;
#ifdef _LP64
typedef long jlong;
#else
typedef long long jlong;
#endif
typedef signed char jbyte;

#endif /* !_JAVASOFT_JNI_MD_H_ */
