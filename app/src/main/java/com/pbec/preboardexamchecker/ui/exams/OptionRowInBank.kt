package com.pbec.preboardexamchecker.ui.exams

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OptionRowInBank(prefix: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$prefix. ", style = MaterialTheme.typography.bodyLarge)
        MathTextView(
            text = text,
            modifier = Modifier.weight(1f)
        )
    }
}
