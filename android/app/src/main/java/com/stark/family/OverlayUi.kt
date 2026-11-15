package com.stark.family

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FG = Color(0xFFEDEDED)
private val DIM = Color(0xFF8A8A8A)
private val CARD = Color(0xFF0A0A0A)

/** Placeholder animated avatar: rotating HUD rings in the character colours (art can replace it later). */
@Composable
fun Avatar(c: Character, avatarSize: Dp, active: Boolean) {
    val t = rememberInfiniteTransition(label = "avatar")
    val spin by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 2200 else 9000, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by t.animateFloat(
        initialValue = 0.85f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(if (active) 450 else 1800), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.size(avatarSize)) {
        val r = this.size.minDimension / 2f
        val ctr = center
        drawCircle(Color.Black, radius = r)
        drawCircle(c.accent.copy(alpha = 0.18f), radius = r * 0.98f)
        rotate(spin, pivot = ctr) {
            for (i in 0 until 3) {
                drawArc(
                    color = c.accent, startAngle = i * 120f, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(ctr.x - r * 0.86f, ctr.y - r * 0.86f),
                    size = Size(r * 1.72f, r * 1.72f),
                    style = Stroke(width = r * 0.11f, cap = StrokeCap.Round),
                )
            }
        }
        rotate(-spin * 1.6f, pivot = ctr) {
            for (i in 0 until 2) {
                drawArc(
                    color = c.accent2, startAngle = i * 180f, sweepAngle = 110f, useCenter = false,
                    topLeft = Offset(ctr.x - r * 0.6f, ctr.y - r * 0.6f),
                    size = Size(r * 1.2f, r * 1.2f),
                    style = Stroke(width = r * 0.07f, cap = StrokeCap.Round),
                )
            }
        }
        drawCircle(c.accent2, radius = r * 0.27f * pulse)
        when (c.id) {
            "vision" -> drawCircle(Color(0xFFF2C14E), radius = r * 0.09f, center = Offset(ctr.x, ctr.y - r * 0.42f))
            "ultron" -> {
                drawCircle(Color(0xFFFF3B30), radius = r * 0.07f, center = Offset(ctr.x - r * 0.12f, ctr.y))
                drawCircle(Color(0xFFFF3B30), radius = r * 0.07f, center = Offset(ctr.x + r * 0.12f, ctr.y))
            }
        }
    }
}

@Composable
fun OverlayUi(
    mode: Mode, ch: Character, said: String, busy: Boolean, waking: Boolean, input: String,
    results: List<String>, pendingText: String?, onDecision: (Boolean) -> Unit,
    hud: AgentHud, onResume: () -> Unit,
    onInput: (String) -> Unit, onSend: (String) -> Unit, onPick: (Character) -> Unit,
    onBubbleTap: () -> Unit, onDrag: (Float, Float) -> Unit, onCollapse: () -> Unit, onStop: () -> Unit,
) {
    StarkTheme(ch) {
        when (mode) {
            Mode.BUBBLE -> Box(
                Modifier.size(60.dp).clip(CircleShape).background(Color.Black)
                    .border(1.5.dp, ch.accent, CircleShape)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onBubbleTap() }) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Avatar(ch, 52.dp, false) }

            Mode.PICKER -> Column(
                Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(CARD)
                    .border(1.dp, ch.accent, RoundedCornerShape(20.dp)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("किसे बुलाएँ?", color = ch.accent, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCollapse) { Text("✕", color = DIM) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Characters.all.forEach { c ->
                        Column(Modifier.clickable { onPick(c) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(c, 52.dp, false)
                            Text(c.name, color = c.accent, fontSize = 12.sp)
                        }
                    }
                }
            }

            Mode.WORKING -> Column(
                Modifier.width(300.dp).clip(RoundedCornerShape(18.dp)).background(CARD)
                    .border(1.dp, ch.accent, RoundedCornerShape(18.dp)).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(ch, 40.dp, true)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("${ch.name} · क़दम ${hud.step}/${hud.max}", color = ch.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(hud.line.ifBlank { "काम कर रहा हूँ…" }, color = FG, fontSize = 13.sp, maxLines = 2)
                    }
                    if (hud.paused) TextButton(onClick = onResume) { Text("▶", color = Color(0xFF5BE38A), fontSize = 20.sp) }
                    TextButton(onClick = onStop) { Text("⏹", color = Color(0xFFFF5D5D), fontSize = 20.sp) }
                }
                if (hud.paused) Text("आपने स्क्रीन छुई, इसलिए रुका हूँ। ▶ से जारी रखिए, ⏹ से रोकिए।", color = DIM, fontSize = 12.sp)
                if (pendingText != null) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(pendingText, color = FG, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ch.accent)
                                    .clickable { onDecision(true) }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("हाँ", color = Color.Black, fontWeight = FontWeight.Bold) }
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, DIM, RoundedCornerShape(12.dp))
                                    .clickable { onDecision(false) }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("नहीं", color = FG) }
                        }
                    }
                }
            }

            Mode.CHAT -> Column(
                Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(CARD)
                    .border(1.dp, ch.accent, RoundedCornerShape(20.dp)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(ch, 64.dp, busy)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, color = ch.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            when {
                                waking -> "Relay जाग रहा है…"
                                busy -> "सोच रहा हूँ…"
                                else -> "सुन रहा हूँ"
                            },
                            color = DIM, fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onCollapse) { Text("✕", color = DIM, fontSize = 18.sp) }
                }
                if (said.isNotEmpty()) {
                    Box(Modifier.padding(top = 8.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        Text(said, color = FG, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                } else if (!busy) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("नमस्ते", "आज क्या करूँ?").forEach { s ->
                            Box(
                                Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, ch.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                    .clickable { onSend(s) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            ) { Text(s, color = ch.accent, fontSize = 13.sp) }
                        }
                    }
                }
                results.forEach { line ->
                    Text(
                        line, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                        color = if (line.startsWith("✓")) Color(0xFF5BE38A) else Color(0xFFFF8A80),
                    )
                }
                if (pendingText != null) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text("पूछ रहा हूँ — करूँ?", color = DIM, fontSize = 12.sp)
                        Text(pendingText, color = FG, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ch.accent)
                                    .clickable { onDecision(true) }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("हाँ", color = Color.Black, fontWeight = FontWeight.Bold) }
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, DIM, RoundedCornerShape(12.dp))
                                    .clickable { onDecision(false) }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("नहीं", color = FG) }
                        }
                    }
                } else Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = onInput,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("बोलिए…", color = DIM) },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend(input) }),
                    )
                    if (busy) {
                        TextButton(onClick = onStop) { Text("⏹", color = Color(0xFFFF5D5D), fontSize = 22.sp) }
                    } else {
                        TextButton(onClick = { onSend(input) }) { Text("➤", color = ch.accent, fontSize = 22.sp) }
                    }
                }
            }
        }
    }
}
