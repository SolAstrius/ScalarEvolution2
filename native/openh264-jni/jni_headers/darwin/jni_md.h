/*
 * jni_md.h for darwin / macOS — vendored so the openh264-jni Makefile
 * can cross-compile from a Linux runner whose JDK ships only
 * include/linux/jni_md.h. Content is the canonical OpenJDK darwin
 * header — a thin Mach-O visibility shim around libc primitives.
 */

#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT     __attribute__((visibility("default")))
#define JNIIMPORT     __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
#ifdef _LP64
typedef long jlong;
#else
typedef long long jlong;
#endif
typedef signed char jbyte;

#endif /* !_JAVASOFT_JNI_MD_H_ */
