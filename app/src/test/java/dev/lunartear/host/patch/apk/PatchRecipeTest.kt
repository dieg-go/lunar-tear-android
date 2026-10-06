package dev.lunartear.host.patch.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shape of the metadata recipe: the four replacements must stay
 * byte-for-byte the reference patcher's, and the Facebook redirect must *not*
 * appear here.
 */
class PatchRecipeTest {

    private val local = PatchTarget(host = "127.0.0.1", grpcPort = 8003, cdnPort = 8080)

    @Test
    fun `the recipe is exactly the reference four strings, with or without the auth host`() {
        val replacements = PatchRecipe.replacements(local)
        assertEquals(4, replacements.size)
        assertEquals(
            listOf(
                PatchRecipe.GRPC_HOST_ORIGINAL,
                PatchRecipe.DATABASE_ORIGINAL,
                PatchRecipe.WEB_BASE_ORIGINAL,
                PatchRecipe.RESOURCES_ORIGINAL,
            ),
            replacements.map { it.old },
        )
        assertTrue(PatchRecipe.problems(local).isEmpty())

        // Enabling the Facebook redirect adds nothing to global-metadata.dat: the
        // Unity/C# graph URL cannot be pointed at a local http port, so rewriting
        // only its domain breaks the login instead of redirecting it. The whole
        // redirect happens in the Java SDK's smali - see SmaliPatches.
        val withAuth = PatchRecipe.replacements(local.copy(authHost = "127.0.0.1:3000"))
        assertEquals(replacements, withAuth)
        assertTrue(PatchRecipe.problems(local.copy(authHost = "127.0.0.1:3000")).isEmpty())
    }

    @Test
    fun `the smali redirect covers everything the reference rewrites`() {
        val host = "127.0.0.1:3000"
        val replacements = SmaliPatches.stringReplacements(host)
        val olds = replacements.map { it.first }

        listOf(
            "\"m.%s\"",
            "\"https://graph.%s\"",
            "\"https://graph-video.%s\"",
            "\"https\"",
            "\"com.facebook.katana\"",
            "\"com.facebook.orca\"",
            "\"www.facebook.com\"",
            "\"facebook.com\"",
        ).forEach { literal ->
            assertTrue("the reference rewrites $literal; the port must too", literal in olds)
        }

        // The auth host is substituted for every null replacement, and it is not
        // length-constrained any more: smali reassembles the class.
        assertEquals("\"$host\"", replacements.first { it.first == "\"facebook.com\"" }.second)
        assertEquals("\"$host\"", replacements.first { it.first == "\"www.facebook.com\"" }.second)
        assertTrue(
            "the auth host must carry its port",
            replacements.first { it.first == "\"facebook.com\"" }.second.contains(":3000"),
        )
    }

    @Test
    fun `an auth host without a port is reported`() {
        val problems = PatchRecipe.problems(local.copy(authHost = "127.0.0.1"))
        assertTrue("expected a port problem, got $problems", problems.any { it.contains("must include a port") })
    }

    @Test
    fun `a metadata replacement that does not fit is still reported`() {
        val problems = PatchRecipe.problems(local.copy(host = "a-very-long-hostname-that-cannot-fit.example"))
        assertTrue("expected a size problem, got $problems", problems.any { it.contains("does not fit") })
    }
}
