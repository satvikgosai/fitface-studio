package dev.fitface.studio.core.delivery

import com.samsung.android.sdk.SsdkInterface
import com.samsung.android.sdk.SsdkUnsupportedException
import com.samsung.android.sdk.SsdkVendorCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AccessorySdkDependencyTest {
    @Test
    fun resolvedBaseSdkHasTheAccessoryRequiredJvmAbi() {
        val sdkInterface = SsdkInterface::class.java
        assertNotNull(sdkInterface.getMethod("getVersionCode"))
        assertNotNull(sdkInterface.getMethod("getVersionName"))
        assertNotNull(sdkInterface.getMethod("initialize", android.content.Context::class.java))
        assertNotNull(sdkInterface.getMethod("isFeatureEnabled", Int::class.javaPrimitiveType))

        assertEquals(
            0,
            SsdkUnsupportedException::class.java
                .getField("VENDOR_NOT_SUPPORTED")
                .getInt(null),
        )
        assertNotNull(
            SsdkVendorCheck::class.java.getMethod("isSamsungDevice"),
        )
    }
}
