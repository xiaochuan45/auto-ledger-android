package io.github.xiaochuan45.autoledger.capture

import org.junit.Assert.*
import org.junit.Test

class SelectedReceiptNodePolicyTest {
    private fun access(pkg: String? = "fictional.app", window: Int = 8, visible: Boolean = false,
        editable: Boolean = false, password: Boolean = false, type: String? = "android.widget.FrameLayout", children: Int = 4) =
        SelectedReceiptNodePolicy.access(pkg, "fictional.app", window, 8, visible, editable, password, type, children)

    @Test fun onlyHiddenStructuralContainersWithChildrenCanRouteToVisibleDescendants() {
        assertEquals(ReceiptNodeAccess.CHILDREN_ONLY, access())
        assertEquals(ReceiptNodeAccess.CHILDREN_ONLY, access(pkg = null))
        assertEquals(ReceiptNodeAccess.REJECT, access(children = 0))
        assertEquals(ReceiptNodeAccess.REJECT, access(type = "android.widget.TextView"))
        assertEquals(ReceiptNodeAccess.REJECT, access(type = "fictional.UnknownView"))
        assertEquals(ReceiptNodeAccess.REJECT, access(type = null))
    }
    @Test fun visibleFieldDoesNotNeedAContainerButStillMustMeetOwnershipAndSensitiveGuards() {
        assertEquals(ReceiptNodeAccess.VISIBLE_FIELDS, access(visible = true, type = "android.widget.TextView", children = 0))
        assertEquals(ReceiptNodeAccess.REJECT, access(visible = true, pkg = "fictional.other"))
        assertEquals(ReceiptNodeAccess.REJECT, access(visible = true, window = 9))
        assertEquals(ReceiptNodeAccess.REJECT, access(visible = true, editable = true))
        assertEquals(ReceiptNodeAccess.REJECT, access(visible = true, password = true))
    }
    @Test fun hiddenForeignWindowsAndSensitiveAncestorsCannotBeTraversed() {
        assertEquals(ReceiptNodeAccess.REJECT, access(pkg = "fictional.other"))
        assertEquals(ReceiptNodeAccess.REJECT, access(window = 9))
        assertEquals(ReceiptNodeAccess.REJECT, access(editable = true))
        assertEquals(ReceiptNodeAccess.REJECT, access(password = true))
        assertEquals(ReceiptNodeAccess.REJECT, access(type = "fictional.EditTextWrapper"))
    }
}
