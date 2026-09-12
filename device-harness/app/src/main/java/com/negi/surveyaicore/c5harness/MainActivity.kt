package com.negi.surveyaicore.c5harness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.negi.surveyaicore.SurveyAICore
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import com.negi.surveyaicore.SurveyAICoreResult

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val publicApiTypeCount = arrayOf(
            SurveyAICore::class.java,
            SurveyAICoreConfig::class.java,
            SurveyAICoreAccelerator::class.java,
            SurveyAICoreResult::class.java,
        ).size

        findViewById<TextView>(R.id.status).text = getString(
            R.string.status_ready,
            publicApiTypeCount,
        )
    }
}
