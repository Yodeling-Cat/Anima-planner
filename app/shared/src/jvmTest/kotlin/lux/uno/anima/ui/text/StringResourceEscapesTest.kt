package lux.uno.anima.ui.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That no string carries an Android-style backslash escape.
 *
 * `\'` and `\"` are Android's resource syntax, and an editor that knows `strings.xml` offers
 * them without being asked. Compose Resources does not unescape them, so they reach the
 * screen as backslashes — which is how the price note came to read `today\'s price` in all
 * three languages. XML needs no escape for either character; the fix is always to delete it.
 */
class StringResourceEscapesTest {

    @Test
    fun noStringCarriesABackslashEscape() {
        val offenders = resourceFiles().flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> line.contains(BACKSLASH) }
                .map { (index, line) -> "${file.parentFile.name}/${file.name}:${index + 1} $line" }
        }
        assertTrue(
            offenders.isEmpty(),
            "Backslash escapes reach the screen verbatim:\n" + offenders.joinToString("\n"),
        )
    }

    private fun resourceFiles(): List<File> {
        // Gradle runs a test with the module directory as its working directory.
        val resources = File("src/commonMain/composeResources")
        assertTrue(resources.isDirectory, "No resources at ${resources.absolutePath}")

        val files = resources
            .listFiles { candidate: File -> candidate.isDirectory && candidate.name.startsWith("values") }
            ?.mapNotNull { folder -> File(folder, "strings.xml").takeIf(File::isFile) }
            .orEmpty()
        assertTrue(files.size >= 3, "Expected one strings.xml per language but found ${files.size}")
        return files
    }

    private companion object {
        const val BACKSLASH = "\\"
    }
}
