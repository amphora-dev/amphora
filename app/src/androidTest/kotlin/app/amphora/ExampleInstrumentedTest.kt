package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun packageIsCorrect() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("app.amphora", ctx.packageName)
    }
}
