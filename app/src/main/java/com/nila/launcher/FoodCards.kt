package com.nila.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** What the food cards can ask NILA to do next. Implemented in MainActivity. */
internal interface FoodActions {
    fun pickAddress(address: FoodAddress, query: String)
    fun addAddress(query: String, form: NewAddressForm)
    fun openRestaurant(address: FoodAddress, restaurant: FoodRestaurant, query: String)
    fun changeItem(address: FoodAddress, restaurant: FoodRestaurant, item: FoodItem, delta: Int)
    fun changeLine(address: FoodAddress, restaurant: FoodRestaurant, itemId: String, delta: Int)
    fun checkout(address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView)
    fun place(address: FoodAddress, cart: FoodCartView, option: FoodPayOption)
    fun openUpi(link: String)
    fun checkPayment(pending: PendingPayment)
    fun track(orderId: String?)
}

private val RatingGreen = Color(0xFF1BA672)
private val VegGreen = Color(0xFF0F8A65)
private val NonVegRed = Color(0xFFE43B4F)
private val CommitInk = Color(0xFF321F19)

@Composable
internal fun FoodCardView(card: FoodCard, actions: FoodActions) {
    when (card) {
        is FoodCard.Addresses -> AddressesCard(card, actions)
        is FoodCard.Restaurants -> RestaurantsCard(card, actions)
        is FoodCard.Menu -> MenuCard(card, actions)
        is FoodCard.Cart -> CartCard(card, actions)
        is FoodCard.Payment -> PaymentCard(card, actions)
        is FoodCard.Order -> OrderCard(card, actions)
        is FoodCard.Problem -> Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SWIGGY", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(card.message, color = Paper)
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).background(Lime, CircleShape))
        Text("SWIGGY · LIVE", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Step $step of 5", color = Muted, fontSize = 10.sp)
    }
    Spacer(Modifier.height(4.dp))
    Text(title, color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
}

@Composable
private fun FoodImage(url: String?, modifier: Modifier) {
    if (url == null) {
        Box(modifier.background(Panel), contentAlignment = Alignment.Center) {
            Text("🍽", fontSize = 22.sp)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Panel),
        )
    }
}

@Composable
private fun RatingBadge(rating: String) {
    if (rating.isBlank()) return
    Row(
        Modifier.background(RatingGreen, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("★ $rating", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VegMark(veg: Boolean?) {
    if (veg == null) return
    val color = if (veg) VegGreen else NonVegRed
    Box(
        Modifier.size(14.dp).border(1.5.dp, color, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(6.dp).background(color, CircleShape)) }
}

// ---- Step 1: address ---------------------------------------------------------

@Composable
private fun AddressesCard(card: FoodCard.Addresses, actions: FoodActions) {
    var showForm by rememberSaveable { mutableStateOf(card.addresses.isEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(1, "Where should I deliver?")
        if (card.addresses.isEmpty()) {
            Text("No saved addresses on your Swiggy account yet. Add one below.", color = Muted, fontSize = 13.sp)
        }
        card.addresses.forEach { address ->
            Surface(
                color = CardInk,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { actions.pickAddress(address, card.query) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).background(Panel, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (address.label.lowercase()) {
                                "home" -> "⌂"
                                "work", "office" -> "▣"
                                else -> "●"
                            },
                            color = Lime,
                            fontSize = 16.sp,
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(address.label, color = Paper, fontWeight = FontWeight.SemiBold)
                        Text(address.line, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Deliver here", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showForm) {
            NewAddressFormCard(onSave = { actions.addAddress(card.query, it) })
        } else {
            OutlinedButton(
                onClick = { showForm = true },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
            ) { Text("+ Add new address", color = Lime) }
        }
    }
}

@Composable
private fun NewAddressFormCard(onSave: (NewAddressForm) -> Unit) {
    var house by rememberSaveable { mutableStateOf("") }
    var street by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("Bengaluru") }
    var pincode by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("HOME") }
    var saving by remember { mutableStateOf(false) }
    val valid = house.isNotBlank() && street.isNotBlank() && city.isNotBlank() &&
        pincode.length == 6 && name.isNotBlank() && phone.filter(Char::isDigit).length == 10

    Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New address", color = Paper, fontWeight = FontWeight.SemiBold)
            FormField("House / flat / floor", house) { house = it }
            FormField("Street and area", street) { street = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { FormField("City", city) { city = it } }
                Box(Modifier.weight(1f)) {
                    FormField("Pincode", pincode, KeyboardType.Number) { pincode = it.filter(Char::isDigit).take(6) }
                }
            }
            FormField("Your name", name) { name = it }
            FormField("Phone", phone, KeyboardType.Phone) { phone = it.filter { c -> c.isDigit() || c == '+' }.take(13) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HOME" to "Home", "WORK" to "Work", "OTHER" to "Other").forEach { (value, label) ->
                    val selected = category == value
                    Surface(
                        color = if (selected) Lime else Panel,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable { category = value },
                    ) {
                        Text(
                            label,
                            color = if (selected) Ink else Paper,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Button(
                onClick = {
                    saving = true
                    onSave(NewAddressForm(house.trim(), street.trim(), city.trim(), pincode, name.trim(), phone.filter(Char::isDigit).takeLast(10), category))
                },
                enabled = valid && !saving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (saving) "Saving to Swiggy…" else "Save and deliver here") }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    )
}

// ---- Step 2: restaurants -----------------------------------------------------

@Composable
private fun RestaurantsCard(card: FoodCard.Restaurants, actions: FoodActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepHeader(2, "${card.restaurants.size} places for \"${card.query}\"")
        Text("Delivering to ${card.address.label} · ${card.address.line}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.restaurants.take(6).forEach { restaurant ->
            Surface(
                color = CardInk,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (restaurant.open) 1f else 0.45f)
                    .clickable(enabled = restaurant.open) { actions.openRestaurant(card.address, restaurant, card.query) },
            ) {
                Column {
                    Box {
                        FoodImage(
                            restaurant.imageUrl,
                            Modifier.fillMaxWidth().aspectRatio(16f / 8f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                        )
                        if (restaurant.offer.isNotBlank()) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(restaurant.offer.uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            }
                        }
                        if (!restaurant.open) {
                            Text(
                                "Closed now",
                                color = Paper,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(restaurant.name, color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RatingBadge(restaurant.rating)
                            Text(listOf(restaurant.eta, restaurant.distance).filter(String::isNotBlank).joinToString(" · "), color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (restaurant.cuisines.isNotBlank()) {
                            Text(restaurant.cuisines, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (restaurant.costForTwo.isNotBlank()) Text(restaurant.costForTwo, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---- Step 3: menu ------------------------------------------------------------

@Composable
private fun MenuCard(card: FoodCard.Menu, actions: FoodActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StepHeader(3, card.restaurant.name)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatingBadge(card.restaurant.rating)
            Text(card.restaurant.eta, color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        card.items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = Panel, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    VegMark(item.veg)
                    Text(item.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(item.price, color = Paper, fontSize = 14.sp)
                    if (item.description.isNotBlank()) {
                        Text(item.description, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                    }
                }
                Box(Modifier.width(118.dp).height(124.dp)) {
                    FoodImage(item.imageUrl, Modifier.size(118.dp, 108.dp).clip(RoundedCornerShape(14.dp)))
                    Surface(
                        color = Paper,
                        shape = RoundedCornerShape(10.dp),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(92.dp)
                            .clickable { actions.changeItem(card.address, card.restaurant, item, +1) },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("ADD", color = VegGreen, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            if (item.customizable) Text("default options", color = Color.Gray, fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---- Step 4: cart ------------------------------------------------------------

@Composable
private fun CartCard(card: FoodCard.Cart, actions: FoodActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(4, "Your cart")
        Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(card.cart.restaurantName, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                card.cart.lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.name, color = Paper, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        Row(
                            Modifier.border(1.dp, Lime.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "−",
                                color = Lime,
                                fontSize = 18.sp,
                                modifier = Modifier.clickable { actions.changeLine(card.address, card.restaurant, line.itemId, -1) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                            Text("${line.quantity}", color = Paper, fontWeight = FontWeight.Bold)
                            Text(
                                "+",
                                color = Lime,
                                fontSize = 18.sp,
                                modifier = Modifier.clickable { actions.changeLine(card.address, card.restaurant, line.itemId, +1) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                        Text(line.price, color = Paper, fontSize = 14.sp, modifier = Modifier.width(64.dp).padding(start = 8.dp))
                    }
                }
                HorizontalDivider(color = Panel)
                card.cart.bill.forEach { (label, value) ->
                    Row {
                        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(value, color = Muted, fontSize = 13.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("To pay", color = Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(card.cart.total, color = Paper, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Text("Deliver to ${card.address.label} · ${card.address.line}", color = Muted, fontSize = 12.sp, maxLines = 2)
            }
        }
        Button(
            onClick = { actions.checkout(card.address, card.restaurant, card.cart) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = card.cart.lines.isNotEmpty(),
        ) { Text("Choose payment · ${card.cart.total}", fontWeight = FontWeight.Bold) }
    }
}

// ---- Step 5: payment and the Commit gate --------------------------------------

@Composable
private fun PaymentCard(card: FoodCard.Payment, actions: FoodActions) {
    var selected by remember { mutableStateOf(card.options.firstOrNull()) }
    var armed by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    // The second tap must come soon after the first; otherwise the gate closes again.
    LaunchedEffect(armed) {
        if (armed) {
            delay(5_000)
            armed = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(5, "How do you want to pay?")
        card.options.forEach { option ->
            val isSelected = selected == option
            Surface(
                color = CardInk,
                shape = RoundedCornerShape(14.dp),
                border = if (isSelected) BorderStroke(1.5.dp, Lime) else null,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !placed) {
                    selected = option
                    armed = false
                },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(18.dp).border(2.dp, if (isSelected) Lime else Muted, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (isSelected) Box(Modifier.size(8.dp).background(Lime, CircleShape)) }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(option.label, color = Paper, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (option.method.equals("Cash", true)) "Pay the delivery partner. Stays inside NILA."
                            else "Opens your UPI app for this one payment.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Surface(color = CommitInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("REAL ORDER · CONFIRM BEFORE PLACING", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "${card.cart.lines.sumOf { it.quantity }} items from ${card.restaurant.name}",
                    color = Paper,
                    fontWeight = FontWeight.SemiBold,
                )
                card.cart.lines.forEach { Text("${it.quantity} × ${it.name}", color = Muted, fontSize = 12.sp) }
                Text("Deliver to ${card.address.label} · ${card.address.line}", color = Muted, fontSize = 12.sp)
                Text(card.cart.total, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("This places a real Swiggy order and charges you.", color = Muted, fontSize = 12.sp)
                Button(
                    onClick = {
                        val option = selected ?: return@Button
                        if (!armed) {
                            armed = true
                        } else {
                            placed = true
                            actions.place(card.address, card.cart, option)
                        }
                    },
                    enabled = selected != null && !placed,
                    colors = ButtonDefaults.buttonColors(containerColor = if (armed) Orange else Lime, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        when {
                            placed -> "Placing order…"
                            armed -> "Tap again to place · ${card.cart.total}"
                            else -> "Place order · ${card.cart.total}"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ---- Order result ------------------------------------------------------------

@Composable
private fun OrderCard(card: FoodCard.Order, actions: FoodActions) {
    Surface(
        color = if (card.placed) Color(0xFF16261D) else CardInk,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).background(if (card.placed) RatingGreen else Panel, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(if (card.placed) "✓" else "₹", color = Color.White, fontWeight = FontWeight.Black) }
                Text(card.title, color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            }
            Text(card.detail, color = Muted, fontSize = 13.sp)
            card.orderId?.let { Text("Order $it", color = Muted, fontSize = 11.sp) }
            val pending = card.pending
            if (pending != null) {
                pending.upiLink?.let { link ->
                    Button(onClick = { actions.openUpi(link) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text("Open UPI app to pay")
                    }
                }
                OutlinedButton(
                    onClick = { actions.checkPayment(pending) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("I've paid — check status", color = Lime) }
            } else if (card.placed) {
                OutlinedButton(
                    onClick = { actions.track(card.orderId) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Track order", color = Lime) }
            }
        }
    }
}
