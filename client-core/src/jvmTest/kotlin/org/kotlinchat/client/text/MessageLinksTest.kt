package org.kotlinchat.client.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageLinksTest {

    private fun urls(text: String): List<String> = MessageLinks.find(text).map { it.url }

    @Test
    fun `finds http and https urls`() {
        val text = "see https://example.com/a?b=1#c and http://other.test/path"
        assertEquals(
            listOf("https://example.com/a?b=1#c", "http://other.test/path"),
            urls(text),
        )
    }

    @Test
    fun `ranges point at the exact text`() {
        val text = "go https://example.com/x now"
        val link = MessageLinks.find(text).single()
        assertEquals("https://example.com/x", text.substring(link.start, link.end))
        assertEquals(3, link.start)
    }

    @Test
    fun `scheme is case insensitive`() {
        assertEquals(listOf("HTTPS://Example.COM/Path"), urls("HTTPS://Example.COM/Path"))
    }

    @Test
    fun `sentence punctuation stays outside the link`() {
        assertEquals(
            listOf("https://example.com/a", "http://other.test"),
            urls("Look: https://example.com/a, then http://other.test!"),
        )
    }

    @Test
    fun `unbalanced closing brackets are excluded`() {
        assertEquals(listOf("https://example.com"), urls("(https://example.com)"))
        assertEquals(listOf("https://example.com/x"), urls("[https://example.com/x]"))
        assertEquals(listOf("https://example.com/x"), urls("https://example.com/x)."))
    }

    @Test
    fun `balanced brackets inside URLs are preserved`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Function_(mathematics)"),
            urls("See https://en.wikipedia.org/wiki/Function_(mathematics) for details."),
        )
        assertEquals(
            listOf("https://example.com/a_(b)"),
            urls("(https://example.com/a_(b))"),
        )
    }

    @Test
    fun `queries fragments and IPv6 hosts are kept`() {
        assertEquals(
            listOf("https://example.com/path?q=a(b)&x=1#frag"),
            urls("https://example.com/path?q=a(b)&x=1#frag"),
        )
        assertEquals(listOf("http://[::1]:8080/x"), urls("http://[::1]:8080/x"))
    }

    @Test
    fun `unsafe and shorthand forms are not links`() {
        assertTrue(urls("javascript:alert(1)").isEmpty())
        assertTrue(urls("file:///etc/passwd").isEmpty())
        assertTrue(urls("mailto:person@example.com").isEmpty())
        assertTrue(urls("www.example.com").isEmpty())
        assertTrue(urls("example.com").isEmpty())
    }

    @Test
    fun `a url with no authority is not a link`() {
        assertTrue(urls("http://").isEmpty())
        assertTrue(urls("https:///path").isEmpty())
        assertTrue(urls("http://?query").isEmpty())
        assertTrue(urls("http://#fragment").isEmpty())
    }

    @Test
    fun `a scheme inside a word is ignored`() {
        assertTrue(urls("xhttps://example.com").isEmpty())
        assertTrue(urls("gohttp://example.com").isEmpty())
    }

    @Test
    fun `urls next to emoji and non latin text are found`() {
        val text = "👍 https://example.com/путь 😀"
        assertEquals(listOf("https://example.com/путь"), urls(text))
    }

    @Test
    fun `urls are not joined across a line break`() {
        assertEquals(listOf("https://example"), urls("https://example\n.com/more"))
    }

    @Test
    fun `a message without urls has no links`() {
        assertTrue(urls("hello there").isEmpty())
        assertTrue(MessageLinks.find("").isEmpty())
    }
}
