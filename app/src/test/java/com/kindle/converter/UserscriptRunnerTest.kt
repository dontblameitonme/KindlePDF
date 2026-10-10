package com.kindle.converter

import com.kindle.converter.web.webview.UserscriptRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserscriptRunnerTest {

    @Test
    fun testMatchPattern() {
        val script = """
            // ==UserScript==
            // @name         骚扰拦截
            // @version      1.0.0
            // @author       tester
            // @match        *://*.zhihu.com/*
            // @match        https://mp.weixin.qq.com/*
            // @match        *://*.csdn.net/*
            // ==/UserScript==
        """.trimIndent()

        val meta = UserscriptRunner.parseMeta(script)
        assertNotNull(meta)
        assertEquals("骚扰拦截", meta!!.name)
        assertEquals("1.0.0", meta.version)
        assertEquals(3, meta.matches.size)

        assertTrue(UserscriptRunner.matchesUrl(script, "https://zhuanlan.zhihu.com/p/123456"))
        assertTrue(UserscriptRunner.matchesUrl(script, "http://www.zhihu.com/question/123"))
        assertTrue(UserscriptRunner.matchesUrl(script, "https://mp.weixin.qq.com/s/abcdef"))
        assertTrue(UserscriptRunner.matchesUrl(script, "https://blog.csdn.net/user/article/details/123"))

        assertFalse(UserscriptRunner.matchesUrl(script, "https://github.com/torvalds/linux"))
        assertFalse(UserscriptRunner.matchesUrl(script, "https://baidu.com/s?wd=test"))
    }
}
