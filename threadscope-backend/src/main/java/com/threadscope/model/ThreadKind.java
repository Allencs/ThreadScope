package com.threadscope.model;

/**
 * 线程类别 — 区分平台线程与 JDK 21+ 的虚拟线程。
 *
 * PLATFORM: 普通平台线程 (与 OS 线程一一对应)
 * VIRTUAL:  虚拟线程 (仅 jcmd Thread.dump_to_file 能导出未挂载的虚拟线程)
 * CARRIER:  当前正挂载着虚拟线程的载体线程 (jstack 中带 "Carrying virtual thread #N")
 */
public enum ThreadKind {
    PLATFORM, VIRTUAL, CARRIER
}
