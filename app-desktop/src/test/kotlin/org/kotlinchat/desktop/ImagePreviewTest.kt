package org.kotlinchat.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagePreviewTest {

    @Test
    fun `large images become a small decodable jpeg preview`() {
        val directory = Files.createTempDirectory("kc-preview")
        val file = directory.resolve("photo.png")
        try {
            val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(30, 120, 210)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.dispose()
            ImageIO.write(image, "png", file.toFile())

            val preview = assertNotNull(createImagePreview(file.toString()))
            assertTrue(preview.size <= 96 * 1024, "preview was ${preview.size} bytes")
            val decoded = assertNotNull(decodePreviewBitmap(preview))
            assertTrue(decoded.width <= 360 && decoded.height <= 360)

            assertNull(createImagePreview(directory.resolve("notes.txt").toString()))
        } finally {
            file.toFile().delete()
            directory.toFile().delete()
        }
    }
}
