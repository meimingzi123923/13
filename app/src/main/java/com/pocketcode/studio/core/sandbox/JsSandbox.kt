package com.pocketcode.studio.core.sandbox

import app.cash.quickjs.QuickJs

/**
 * 暴露给 QuickJS 的宿主接口。
 * QuickJS 的 set(name, Class, obj) 要求接口无重载、参数/返回值仅为基本类型或 String。
 */
interface JsSandboxHost {
    fun print(msg: String)
}

/**
 * 进程内 JavaScript 沙盒：基于 QuickJS，在 App 自己的进程里执行 JS。
 *
 * 它不依赖系统 shell，也不需要 node —— 是「在应用沙盒中运行」这一诉求
 * 立刻就可用的一种运行时（对 .js 文件默认走这里）。
 */
class JsSandbox(private val onOutput: (String) -> Unit) {

    private val engine: QuickJs = QuickJs.create()

    init {
        val host = object : JsSandboxHost {
            override fun print(msg: String) {
                onOutput(msg)
            }
        }
        engine.set("__host", JsSandboxHost::class.java, host)
        engine.evaluate(BOOTSTRAP)
    }

    /** 执行一段 JS 源码；返回退出码（0 成功，1 抛异常）。 */
    fun eval(code: String): Int = runCatching {
        engine.evaluate(code)
        0
    }.getOrElse {
        onOutput("\u001b[31m✗ " + (it.message ?: it.toString()) + "\u001b[0m")
        1
    }

    fun close() {
        runCatching { engine.close() }
    }

    companion object {
        /** 为 JS 提供 console.* 与 pcs.print，输出回送到终端。 */
        private val BOOTSTRAP = """
            var console = {
              log: function () { __host.print(Array.prototype.join.call(arguments, ' ')); },
              info: function () { __host.print(Array.prototype.join.call(arguments, ' ')); },
              warn: function () { __host.print(Array.prototype.join.call(arguments, ' ')); },
              error: function () { __host.print(Array.prototype.join.call(arguments, ' ')); },
              debug: function () { __host.print(Array.prototype.join.call(arguments, ' ')); }
            };
            var pcs = { print: function () { __host.print(Array.prototype.join.call(arguments, ' ')); } };
        """.trimIndent()
    }
}