package dev.anthonyta.mandosteps

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Shown when Health Connect asks why the app wants the steps permission. */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.textSize = 16f
        view.text = getString(R.string.rationale)
        setContentView(view)
    }
}
