package com.nila.launcher.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.launcher.ActionRadius
import com.nila.launcher.CardInk
import com.nila.launcher.Ink
import com.nila.launcher.Lime
import com.nila.launcher.Muted
import com.nila.launcher.Panel
import com.nila.launcher.Paper
import com.nila.launcher.PrimaryAction
import com.nila.launcher.SectionLabel
import com.nila.launcher.TextAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A user action the screen hands back to AppRuntime.dispatch. */
fun interface MiniAppActions {
    fun dispatch(action: JsonObject, target: Item?, values: Map<String, JsonElement>)
}

private val HeroSize = 48.sp
private val CounterSize = 96.sp
private val GutterWidth = 20.dp
private val RowIconSize = 34.dp
private val ThinBarHeight = 8.dp
private val WeekBarsHeight = 110.dp
private val AddButtonSize = 56.dp
private val ChipShape = RoundedCornerShape(999.dp)
/** Category colours after Lime, in the order the approved mockup used them. */
private val SegmentColors = listOf(Lime, Color(0xFF8FB3FF), Color(0xFFFF9A6B), Color(0xFF6B6B62))
private val DayLabel = Regex("\\d{4}-\\d{2}-\\d{2}")
private const val MIN_BAR_FRACTION = 0.04f

/**
 * Draws any validated recipe in the approved mini-app direction (2026-10-11): huge number on top with NILA's insight
 * line, one thin chart, a dense edge-to-edge list, a lime + button. Layout only: ScreenState is already formatted.
 */
@Composable
fun MiniAppScreen(state: ScreenState, actions: MiniAppActions, onGrant: (String) -> Unit, modifier: Modifier = Modifier) {
    var formOpen by remember { mutableStateOf(false) }
    val form = state.blocks.filterIsInstance<Block.AddForm>().firstOrNull()
    val counter = state.blocks.filterIsInstance<Block.Counter>().firstOrNull()
    Box(modifier.fillMaxSize().background(Ink)) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            items(state.blocks.filter { it !is Block.AddForm }) { block -> BlockView(block, actions, onGrant) }
            item { Spacer(Modifier.height(AddButtonSize + 32.dp)) }
        }
        when {
            counter != null -> PrimaryAction(
                label = "+ 1",
                onClick = { actions.dispatch(counter.step, null, emptyMap()) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(GutterWidth).fillMaxWidth(),
            )
            form != null && !formOpen -> Surface(
                color = Lime,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(GutterWidth).size(AddButtonSize).clickable { formOpen = true },
            ) { Box(contentAlignment = Alignment.Center) { Text("+", color = Ink, fontSize = 28.sp) } }
        }
        if (form != null && formOpen) {
            AddSheet(form, Modifier.align(Alignment.BottomCenter)) { values ->
                actions.dispatch(form.submit, null, values)
                formOpen = false
            }
        }
    }
}

@Composable
private fun BlockView(block: Block, actions: MiniAppActions, onGrant: (String) -> Unit) {
    val gutter = Modifier.padding(horizontal = GutterWidth)
    when (block) {
        is Block.Hero -> Column(gutter) {
            SectionLabel(block.label)
            Text(block.value, color = Paper, fontFamily = FontFamily.Serif, fontSize = HeroSize, lineHeight = HeroSize, maxLines = 1)
            block.insight?.let { Text(it, color = Muted, fontSize = 13.sp) }
        }
        is Block.Counter -> Column(gutter) {
            Text(block.value, color = Paper, fontFamily = FontFamily.Serif, fontSize = CounterSize, lineHeight = CounterSize)
            Text(block.label, color = Muted, fontSize = 15.sp)
        }
        is Block.Chart -> if (block.bars.all { DayLabel.matches(it.label) }) DayBars(block.bars, gutter) else SplitBar(block.bars, gutter)
        is Block.ItemList -> Column {
            block.sections.forEach { section ->
                SectionLabel(section.title, gutter.padding(top = 10.dp, bottom = 2.dp))
                section.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = Panel, modifier = gutter)
                    ItemRow(row, block.onCategory, actions, gutter)
                }
            }
            if (block.more > 0) TextAction("See all ${block.more + block.sections.sumOf { it.rows.size }}", {}, gutter.padding(top = 8.dp))
        }
        is Block.Checklist -> Column(gutter) {
            block.rows.forEach { check ->
                LayoutRow(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = check.done,
                        onCheckedChange = { done -> block.toggle?.let { actions.dispatch(it, check.item, mapOf("done" to JsonPrimitive(done))) } },
                        colors = CheckboxDefaults.colors(checkedColor = Lime, checkmarkColor = Ink, uncheckedColor = Muted),
                    )
                    Text(check.label, color = if (check.done) Muted else Paper, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        is Block.Button -> PrimaryAction(block.label, { actions.dispatch(block.action, null, emptyMap()) }, gutter.fillMaxWidth())
        is Block.Note -> Text(block.text, color = Muted, fontSize = 13.sp, modifier = gutter)
        is Block.Blocked -> Column(gutter.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardInk).padding(16.dp)) {
            Text("This app needs access to your ${block.permission.substringBefore('.')} to show anything.", color = Paper, fontSize = 15.sp)
            TextAction("Grant access", { onGrant(block.permission) }, Modifier.padding(top = 8.dp))
        }
        is Block.AddForm -> Unit
    }
}

@Composable
private fun ItemRow(row: Row, onCategory: JsonObject?, actions: MiniAppActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        LayoutRow(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(RowIconSize).clip(RoundedCornerShape(11.dp)).background(if (row.chips.isEmpty()) Panel else Lime), contentAlignment = Alignment.Center) {
                Text(if (row.chips.isEmpty()) row.title.take(1).uppercase() else "?", color = if (row.chips.isEmpty()) Paper else Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(row.title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = if (row.chips.isEmpty()) row.detail else "Tap a category"
                if (detail.isNotEmpty()) Text(detail, color = if (row.chips.isEmpty()) Muted else Lime, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            row.trailing?.let { Text(it, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        }
        if (row.chips.isNotEmpty() && onCategory != null) {
            LayoutRow(Modifier.padding(start = RowIconSize + 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.chips.filter { it != MiniAppModel.OTHER_CHIP }.forEach { chip ->
                    Text(
                        chip,
                        color = Paper,
                        fontSize = 12.sp,
                        modifier = Modifier.clip(ChipShape).background(Panel).clickable {
                            actions.dispatch(onCategory, row.item, mapOf("category" to JsonPrimitive(chip)))
                        }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Share of each group in one thin stacked bar with a legend, as in the approved money screen. */
@Composable
private fun SplitBar(bars: List<Bar>, modifier: Modifier) {
    val total = bars.sumOf { it.value }.takeIf { it > 0 } ?: return
    val sorted = bars.sortedByDescending { it.value }
    Column(modifier) {
        LayoutRow(Modifier.fillMaxWidth().height(ThinBarHeight).clip(ChipShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            sorted.forEachIndexed { i, bar -> Box(Modifier.weight((bar.value / total).toFloat().coerceAtLeast(MIN_BAR_FRACTION)).fillMaxHeight().background(SegmentColors[i % SegmentColors.size])) }
        }
        LayoutRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            sorted.take(SegmentColors.size).forEachIndexed { i, bar ->
                LayoutRow(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(SegmentColors[i]))
                    Text(" ${bar.label}", color = Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Per-day bars, newest highlighted in lime, as in the approved counter screen. */
@Composable
private fun DayBars(bars: List<Bar>, modifier: Modifier) {
    val sorted = bars.sortedBy { it.label }
    val top = sorted.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: return
    LayoutRow(modifier.fillMaxWidth().height(WeekBarsHeight), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sorted.forEachIndexed { i, bar ->
            Box(
                Modifier.weight(1f).fillMaxHeight((bar.value / top).toFloat().coerceAtLeast(MIN_BAR_FRACTION))
                    .clip(RoundedCornerShape(6.dp)).background(if (i == sorted.lastIndex) Lime else Panel),
            )
        }
    }
}

@Composable
private fun AddSheet(form: Block.AddForm, modifier: Modifier, onSubmit: (Map<String, JsonElement>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(CardInk).padding(GutterWidth),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        form.fields.forEach { field ->
            OutlinedTextField(
                value = values[field.field].orEmpty(),
                onValueChange = { values[field.field] = it },
                label = { Text(field.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PrimaryAction(form.submitLabel, {
            onSubmit(form.fields.associate { it.field to typedInput(values[it.field].orEmpty(), it.type) })
        }, Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(ActionRadius)))
    }
}

