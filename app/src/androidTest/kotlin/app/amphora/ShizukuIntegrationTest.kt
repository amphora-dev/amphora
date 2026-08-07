package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuIntegrationTest {
    @Test
    fun manifestRegistersShizukuBinderProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider =
            context.packageManager.resolveContentProvider(
                "${context.packageName}.shizuku",
                0,
            )
        assertNotNull("Shizuku binder provider is missing from the merged manifest", provider)
    }
}
