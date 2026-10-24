package dev.useunion.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.useunion.FeatureRole
import dev.useunion.Union

/** Plain views on purpose: the sample shows the SDK calls, not a UI toolkit. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.let(Union::handleDeepLink)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "Union sample" })
        root.addView(Button(this).apply {
            text = "Start workout"
            setOnClickListener {
                Union.track("workout_started", mapOf("plan" to "strength", "minutes" to 30), role = FeatureRole.Start)
            }
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Union.screen("Home")
    }
}
