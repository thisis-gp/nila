package com.nila.launcher

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the food cards can ask NILA to do next. Implemented in MainActivity. */
internal interface FoodActions {
    fun locate(query: String)
    /** Every app's price for the cart at checkout, filled in as each app answers. */
    fun quotes(): StateFlow<QuoteBoard>
    fun retryQuote(providerId: String)
    /** Each top search result's price on every signed-in app, by [dishKey] then provider id. */
    fun dishPrices(): StateFlow<Map<String, Map<String, DishPrice>>>
    fun signIn(provider: FoodProvider)
    /** Opens a delivery app NILA can't order on, so the user finishes there. */
    fun openApp(provider: FoodProvider)
    fun applyCoupon(payment: FoodCard.Payment, coupon: FoodCoupon)
    fun back(card: FoodCard)
    /** The same shop on Zomato with its prices for this request; null when Zomato isn't connected. */
    suspend fun zomatoShop(address: FoodAddress, restaurant: FoodRestaurant): ZomatoShop?
    /** The same dish across Zomato near [address]; null when Zomato isn't connected. */
    suspend fun zomatoNearby(address: FoodAddress): ZomatoNearby?
    fun lastResults(): FoodCard.Restaurants?
    fun lastMenu(): FoodCard?
    fun lastCart(): FoodCard.Cart?
    fun changeAddress(query: String)
    fun refine(address: FoodAddress, search: NilaResponse.FoodSearch)
    /** Makes [address] NILA's address and orders there; the other apps get it in the background. */
    fun pickAddress(address: NilaAddress, query: String)
    /** Which apps have NILA's address, for the quiet "Added WeWork Galaxy to Swiggy" note. */
    fun addressSync(): StateFlow<AddressSyncState>
    fun addAddress(query: String, form: NewAddressForm)
    fun openRestaurant(address: FoodAddress, restaurant: FoodRestaurant, query: String)
    fun changeItem(address: FoodAddress, restaurant: FoodRestaurant, item: FoodItem, delta: Int)
    fun changeLine(address: FoodAddress, restaurant: FoodRestaurant, itemId: String, delta: Int)
    fun checkout(address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView)
    fun place(address: FoodAddress, cart: FoodCartView, option: FoodPayOption)
    fun openUpi(link: String)
    fun checkPayment(pending: PendingPayment)
    fun track(orderId: String?)
    /** Saves "I like <dish>" as a Preference fact; only ever called from the user's own tap. */
    fun rememberDish(dish: String)
}

private val RatingGreen = Color(0xFF1BA672)
private val VegGreen = Color(0xFF0F8A65)
private val NonVegRed = Color(0xFFE43B4F)

@Composable
internal fun FoodCardView(card: FoodCard, actions: FoodActions) {
    when (card) {
        is FoodCard.Addresses -> AddressesCard(card, actions)
        is FoodCard.Restaurants -> RestaurantsCard(card, actions)
        is FoodCard.Menu -> MenuCard(card, actions)
        is FoodCard.Dishes -> DishesCard(card, actions)
        is FoodCard.Cart -> CartCard(card, actions)
        is FoodCard.Payment -> PaymentCard(card, actions)
        is FoodCard.Order -> OrderCard(card, actions)
        is FoodCard.Problem -> Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(orderingProvider.name, color = Orange)
                Text(card.message, color = Paper)
            }
        }
    }
}

@Composable
internal fun BackLink(label: String, onBack: () -> Unit) {
    Text(
        "‹ $label",
        color = Lime,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
    )
}

@Composable
internal fun StepHeader(step: Int, title: String, source: String = "${orderingProvider.name} · live") {
    SourceLabel(source)
    Spacer(Modifier.height(4.dp))
    Text(title, color = Paper, fontSize = 26.sp, fontFamily = FontFamily.Serif, lineHeight = 30.sp)
}

@Composable
internal fun FoodImage(url: String?, modifier: Modifier) {
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

/** "To WeWork Galaxy · Change", and under it a quiet note when NILA just saved the address in an app. */
@Composable
internal fun DeliverToLine(address: FoodAddress, actions: FoodActions, onChange: () -> Unit) {
    val sync by actions.addressSync().collectAsState()
    Column {
        Row(Modifier.clickable(onClick = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("To ${address.label}", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(" · Change", color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        sync.takeIf { it.address?.line == address.line }?.let(::addedNote)?.let { Text(it, color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun AddressesCard(card: FoodCard.Addresses, actions: FoodActions) {
    var showForm by rememberSaveable { mutableStateOf(card.choices.isEmpty()) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) actions.locate(card.query)
    }
    // Current location comes first, so ask once as soon as the address step appears.
    LaunchedEffect(card) {
        if (!card.locationAllowed && !asked) {
            asked = true
            askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    val nearest = card.here?.let { here -> nilaAddressOf(here).let { spot -> card.choices.firstOrNull { sameAddress(spot, it.address) } } }
    val others = card.choices.filter { it != nearest }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(1, foodIntentTitle(card.intent))
        IntentChips(card.intent, onChange = null)
        Text("Where should I deliver?", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(card.note ?: "Every app delivers to the address you pick here.", color = if (card.note != null) Orange else Muted, fontSize = 13.sp)
        HereCard(card, nearest, actions, onAddHere = { showForm = true }, onAllow = { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) })
        if (others.isNotEmpty()) {
            Text("Your addresses", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    val shown = if (showAll) others else others.take(SAVED_PREVIEW)
                    shown.forEachIndexed { index, choice ->
                        if (index > 0) HorizontalDivider(color = Panel, modifier = Modifier.padding(start = 62.dp))
                        AddressRow(
                            FoodAddress("", choice.address.label, choice.address.line),
                            detail = choice.sources.joinToString(" · "),
                        ) { actions.pickAddress(choice.address, card.query) }
                    }
                    if (!showAll && others.size > SAVED_PREVIEW) {
                        HorizontalDivider(color = Panel)
                        Text(
                            "Show all ${others.size} addresses",
                            color = Lime,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable { showAll = true }.padding(14.dp),
                        )
                    }
                }
            }
        }
        if (showForm) {
            NewAddressFormCard(prefill = card.here, onSave = { actions.addAddress(card.query, it) })
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

private const val SAVED_PREVIEW = 3

/** The phone's own location on top: the saved address the user is at, or a one-tap way to add it. */
@Composable
private fun HereCard(
    card: FoodCard.Addresses,
    nearest: AddressChoice?,
    actions: FoodActions,
    onAddHere: () -> Unit,
    onAllow: () -> Unit,
) {
    val here = card.here
    Surface(
        color = CardInk,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Lime.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(Lime.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("◎", color = Lime, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Current location", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            here != null -> here.title
                            card.locationAllowed -> "Couldn't find you right now"
                            else -> "Location is off for NILA"
                        },
                        color = Paper,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (here != null) {
                        Text(
                            listOf(here.street, here.pincode).filter(String::isNotBlank).joinToString(" · "),
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            when {
                nearest != null -> {
                    Text("You're at ${nearest.address.label} · ${nearest.address.line.substringAfter(": ")}", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Button(
                        onClick = { actions.pickAddress(nearest.address, card.query) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                    ) { Text("Deliver to ${nearest.address.label}", fontWeight = FontWeight.Bold) }
                }
                here != null -> Button(
                    onClick = onAddHere,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                ) { Text("Deliver here · add this address", fontWeight = FontWeight.Bold) }
                !card.locationAllowed -> OutlinedButton(
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Use my current location", color = Lime) }
                else -> Text("Pick an address below.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

/** One address: its label, its line, and [detail] (the apps that have it) when given. */
@Composable
internal fun AddressRow(address: FoodAddress, detail: String? = null, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                when (address.label.lowercase()) {
                    "home" -> "⌂"
                    "work", "office" -> "▣"
                    else -> "●"
                },
                color = Lime,
                fontSize = 15.sp,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(address.label, color = Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(address.line.substringAfter(": "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("›", color = Muted, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NewAddressFormCard(prefill: CurrentPlace?, onSave: (NewAddressForm) -> Unit) {
    var house by rememberSaveable { mutableStateOf("") }
    // Filled from the current location; the user only adds the house number, name and phone.
    var street by rememberSaveable { mutableStateOf(prefill?.let { listOf(it.street, it.area).filter(String::isNotBlank).joinToString(", ") }.orEmpty()) }
    var city by rememberSaveable { mutableStateOf(prefill?.city.orEmpty()) }
    var pincode by rememberSaveable { mutableStateOf(prefill?.pincode?.filter(Char::isDigit)?.take(6).orEmpty()) }
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
            ) { Text(if (saving) "Saving…" else "Save and deliver here") }
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
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(2, if (card.picks.isEmpty()) "Nothing open right now" else foodIntentTitle(card.intent))
        IntentChips(card.intent) { actions.refine(card.address, it) }
        DeliverToLine(card.address, actions) { actions.changeAddress(card.query) }
        card.picks.forEachIndexed { index, (restaurant, reason) ->
            PickCard(restaurant, reason, badge = pickLabel(card.intent).takeIf { index == 0 }) { actions.openRestaurant(card.address, restaurant, card.query) }
        }
        val rest = card.restaurants.filter { r -> card.picks.none { it.first == r } }
        if (rest.isNotEmpty()) {
            if (showAll) {
                Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        rest.forEachIndexed { index, restaurant ->
                            if (index > 0) HorizontalDivider(color = Panel, modifier = Modifier.padding(horizontal = 14.dp))
                            RestaurantRow(restaurant) { actions.openRestaurant(card.address, restaurant, card.query) }
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { showAll = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("See all ${card.restaurants.size} places", color = Lime) }
            }
        }
    }
}

/** One of the best fits: image-led like the compare step in the prototype, with why NILA picked it. */
@Composable
private fun PickCard(restaurant: FoodRestaurant, reason: String, badge: String?, onOpen: () -> Unit) {
    val best = badge != null
    Surface(
        color = CardInk,
        shape = RoundedCornerShape(20.dp),
        border = if (best) BorderStroke(1.5.dp, Lime) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FoodImage(restaurant.imageUrl, Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        restaurant.name,
                        color = Paper,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (restaurant.ad) {
                        Text(
                            "AD",
                            color = Muted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.border(1.dp, Muted.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(reason, color = Paper.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(restaurant.cuisines, restaurant.area).filter(String::isNotBlank).joinToString(" · "),
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Text(
                        badge,
                        color = Ink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 2.dp).background(Lime, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * "What I understood": the request's qualifiers as chips. Removing one searches again without it. The dish
 * itself is already the step's serif title, so it gets no chip of its own.
 */
@Composable
private fun IntentChips(intent: NilaResponse.FoodSearch, onChange: ((NilaResponse.FoodSearch) -> Unit)?) {
    val chips = buildList {
        intent.people?.let { add("For $it" to intent.copy(people = null)) }
        intent.budgetRupees?.let { add("Under ₹$it" to intent.copy(budgetRupees = null)) }
        intent.preference?.let { add(it.replaceFirstChar(Char::uppercase) to intent.copy(preference = null)) }
    }
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        chips.forEach { (label, without) ->
            Surface(
                color = Panel,
                shape = RoundedCornerShape(50),
                modifier = if (onChange != null) Modifier.clickable { onChange(without) } else Modifier,
            ) {
                Text(
                    if (onChange != null) "$label  ✕" else label,
                    color = Paper,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The request in one line, the way a person would say it back: "Biryani for 3 under ₹800". */
internal fun foodIntentTitle(intent: NilaResponse.FoodSearch): String = buildString {
    append(listOfNotNull(intent.preference, intent.query).joinToString(" ").replaceFirstChar(Char::uppercase))
    intent.people?.let { append(" for $it") }
    intent.budgetRupees?.let { append(" under ₹$it") }
}

@Composable
private fun RestaurantRow(restaurant: FoodRestaurant, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (restaurant.open) 1f else 0.45f)
            .clickable(enabled = restaurant.open, onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(92.dp)) {
            FoodImage(restaurant.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
            if (restaurant.offer.isNotBlank()) {
                Text(
                    restaurant.offer.uppercase(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    lineHeight = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    restaurant.name,
                    color = Paper,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (restaurant.ad) {
                    Text(
                        "AD",
                        color = Muted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, Muted.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RatingBadge(restaurant.rating)
                if (restaurant.ratingCount.isNotBlank()) Text("(${restaurant.ratingCount})", color = Muted, fontSize = 11.sp)
                if (restaurant.eta.isNotBlank()) Text("· ${restaurant.eta}", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (restaurant.cuisines.isNotBlank()) {
                Text(restaurant.cuisines, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOf(restaurant.area, restaurant.distance, restaurant.costForTwo).filter(String::isNotBlank).joinToString(" · "),
                color = Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!restaurant.open) Text("Closed now", color = NonVegRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- Step 2: the dish, from every place that has it ---------------------------

private const val DISH_PREVIEW = 6

@Composable
private fun DishesCard(card: FoodCard.Dishes, actions: FoodActions) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val dishPrices by actions.dishPrices().collectAsState()
    val context = LocalContext.current
    val compareApps = foodProviders.filter { it.connected(context) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(2, foodIntentTitle(card.intent))
        IntentChips(card.intent) { actions.refine(card.address, it) }
        DeliverToLine(card.address, actions) { actions.changeAddress(card.query) }
        // The comparison in one line: what the same dish costs across the places that have it.
        val prices = card.dishes.mapNotNull { Regex("""\d+""").find(it.item.chosenSize?.price ?: it.item.price)?.value?.toIntOrNull() }
        if (prices.size > 1) {
            Text(
                "₹${prices.min()}–₹${prices.max()} across ${card.dishes.map { it.restaurant.id }.distinct().size} places",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        val shown = if (showAll) card.dishes else card.dishes.take(DISH_PREVIEW)
        shown.forEachIndexed { index, dish ->
            val compared = dishPrices[dishKey(dishRequest(orderingProvider.id, card.address, dish))]
            val priceLine = compared?.let { found -> dishPriceLine(compareApps.map { it to found[it.id] }) }
            DishRow(dish, priceLine, badge = pickLabel(card.intent).takeIf { index == 0 && dish.caution == null }) { item -> actions.changeItem(card.address, dish.restaurant, item, +1) }
        }
        if (!showAll && card.dishes.size > DISH_PREVIEW) {
            Text(
                "See ${card.dishes.size - DISH_PREVIEW} more",
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showAll = true }.padding(vertical = 4.dp),
            )
        }
        ZomatoNearbyBlock(card, actions)
        actions.lastResults()?.let { results -> BackLink("Browse restaurants instead") { actions.back(results) } }
    }
}

@Composable
private fun DishRow(dish: FoodDish, priceLine: String?, badge: String?, onAdd: (FoodItem) -> Unit) {
    val best = badge != null
    var size by remember(dish.item.id) { mutableStateOf(dish.item.chosenSize) }
    val item = dish.item.copy(chosenSize = size)
    Surface(
        color = CardInk,
        shape = RoundedCornerShape(20.dp),
        border = if (best) BorderStroke(1.5.dp, Lime) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(104.dp).height(116.dp)) {
                FoodImage(item.imageUrl, Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)))
                Surface(
                    color = Paper,
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).width(80.dp).clickable { onAdd(item) },
                ) {
                    Text(
                        "ADD",
                        color = VegGreen,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VegMark(item.veg)
                    if (badge != null) {
                        Text(
                            badge,
                            color = Ink,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier.background(Lime, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(item.name, color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(size?.price ?: item.price, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (item.sizes.size > 1) SizeChips(item.sizes, size) { size = it }
                // The other apps' prices are for the size shown at first; another size has no comparison yet.
                if (priceLine != null && size == dish.item.chosenSize) Text(priceLine, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(dish.reason, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- Step 3: menu ------------------------------------------------------------

@Composable
private fun MenuCard(card: FoodCard.Menu, actions: FoodActions) {
    val zomato = rememberZomatoShops(card.address, listOf(card.restaurant), actions)[card.restaurant.id]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.lastResults()?.let { results -> BackLink("Other places") { actions.back(results) } }
        StepHeader(3, card.restaurant.name)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatingBadge(card.restaurant.rating)
            Text(card.restaurant.eta, color = Muted, fontSize = 12.sp)
        }
        ZomatoShopBlock(zomato, card.items.map { it.name })
        Spacer(Modifier.height(6.dp))
        card.items.forEachIndexed { index, listed ->
            var size by remember(listed.id) { mutableStateOf(listed.chosenSize) }
            val item = listed.copy(chosenSize = size)
            if (index > 0) HorizontalDivider(color = Panel, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    VegMark(item.veg)
                    Text(item.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(size?.price ?: item.price, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (item.sizes.size > 1) SizeChips(item.sizes, size) { size = it }
                    // The same line as under a search result: each app's price for this dish at this shop.
                    zomato?.let { lookup -> dishPriceLine(listOf(orderingProvider to paiseOf(size?.price ?: item.price)?.let { DishPrice.Found(it) }, ZomatoProvider to shopDishPrice(lookup, item.name))) }
                        ?.let { Text(it, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
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
                            if (item.customizable && item.sizes.size <= 1) Text("customisable", color = Color.Gray, fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SizeChips(sizes: List<FoodSize>, selected: FoodSize?, onPick: (FoodSize) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        sizes.take(3).forEach { size ->
            val on = size == selected
            Surface(
                color = if (on) Lime else Panel,
                shape = RoundedCornerShape(50),
                modifier = Modifier.clickable { onPick(size) },
            ) {
                Text(
                    size.name.substringBefore(" ("),
                    color = if (on) Ink else Paper,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

// ---- Step 4: cart ------------------------------------------------------------

@Composable
private fun CartCard(card: FoodCard.Cart, actions: FoodActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(4, "Your cart")
        actions.lastMenu()?.let { menu -> BackLink("Add more") { actions.back(menu) } }
        Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(card.cart.restaurantName, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                card.cart.lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.name, color = Paper, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        QuantityStepper(
                            quantity = line.quantity,
                            onLess = { actions.changeLine(card.address, card.restaurant, line.itemId, -1) },
                            onMore = { actions.changeLine(card.address, card.restaurant, line.itemId, +1) },
                        )
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
                Text("To ${card.address.label} · ${card.address.line.substringAfter(": ")}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    var showAll by remember { mutableStateOf(false) }
    // The second tap must come soon after the first; otherwise the gate closes again.
    LaunchedEffect(armed) {
        if (armed) {
            delay(5_000)
            armed = false
        }
    }
    val board by actions.quotes().collectAsState()
    // This card's own bill stands for the ordering app, so an older card (before a coupon) never shows newer numbers.
    val own = orderingProvider.id to swiggyQuote(card.cart, card.restaurant.eta, card.applied?.code)
    val ranked = board.takeIf { card.compareKey != null && it.key == card.compareKey }?.let { it.copy(quotes = it.quotes + own) }?.ranked()
    // One primary action: order here when the cheapest app is the one NILA orders on, else open the cheapest app.
    val openInstead = ranked?.winner?.provider?.takeIf { !it.canOrder && it.appPackage != null }
    // A late answer can change the cheapest app; the order gate closes so the user sees the new choice first.
    LaunchedEffect(openInstead) { armed = false }
    val upi = card.options.filter { !it.method.equals("Cash", true) }
    val cash = card.options.filter { it.method.equals("Cash", true) }

    @Composable
    fun Group(title: String, options: List<FoodPayOption>) {
        if (options.isEmpty()) return
        Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
        Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                options.forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider(color = Panel, modifier = Modifier.padding(start = 60.dp))
                    PayRow(option, selected == option, enabled = !placed) {
                        selected = option
                        armed = false
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.lastCart()?.let { cart -> BackLink("Edit cart") { actions.back(cart) } }
        StepHeader(5, "Review and pay")
        ranked?.let { CompareSection(it, actions) }
        openInstead?.let { app ->
            Button(
                onClick = { actions.openApp(app) },
                modifier = Modifier.fillMaxWidth().height(PrimaryActionHeight),
                shape = RoundedCornerShape(ActionRadius),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
            ) { Text("Open in ${app.name}", fontWeight = FontWeight.Bold) }
        }
        // One method shown: the one used last time, or the first. The rest stay one tap away.
        if (showAll) {
            Group("PAY BY UPI APP", upi)
            Group("PAY ON DELIVERY", cash)
        } else {
            selected?.let { Group("PAY WITH", listOf(it)) }
            Text(
                "Other ways to pay",
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = !placed) { showAll = true }.padding(vertical = 4.dp),
            )
        }
        OffersBlock(card, enabled = !placed) { actions.applyCoupon(card, it) }
        Spacer(Modifier.height(4.dp))
        Surface(color = CommitInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("REAL ORDER · CONFIRM BEFORE PLACING", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                card.cart.lines.forEach { Text("${it.quantity} × ${it.name}", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Text("To ${card.address.label} · ${card.address.line.substringAfter(": ")}", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(card.cart.total, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text(
                        selected?.let { "  via ${it.label}" }.orEmpty(),
                        color = Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
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
                    colors = when {
                        armed -> ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
                        openInstead == null -> ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                        else -> ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Lime)
                    },
                    border = if (openInstead != null && !armed) BorderStroke(1.dp, Lime.copy(alpha = 0.5f)) else null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        when {
                            placed -> "Placing order…"
                            armed -> "Tap again to order · ${card.cart.total}"
                            else -> "Order on ${orderingProvider.name} · ${card.cart.total}"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text("This places a real ${orderingProvider.name} order and charges you.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

/** The best usable coupon up front with one Apply tap; the rest stay folded. */
@Composable
private fun OffersBlock(card: FoodCard.Payment, enabled: Boolean, onApply: (FoodCoupon) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val applied = card.applied
    if (applied != null) {
        Surface(color = RatingGreen.copy(alpha = 0.18f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "${applied.code} applied" + applied.saving.takeIf(String::isNotBlank)?.let { " · you save $it" }.orEmpty(),
                color = Paper,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
        return
    }
    val usable = card.coupons.filter { it.usable }
    if (usable.isEmpty()) {
        if (card.coupons.isNotEmpty()) Text("No coupon fits this cart yet", color = Muted, fontSize = 12.sp)
        return
    }
    SectionLabel("Offers", Modifier.padding(top = 4.dp))
    Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            (if (showAll) usable else usable.take(1)).forEachIndexed { index, coupon ->
                if (index > 0) HorizontalDivider(color = Panel)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(coupon.code, color = Lime, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
                        Text(coupon.title, color = Paper, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "Apply",
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Lime)
                            .clickable(enabled = enabled) { onApply(coupon) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (!showAll && usable.size > 1) {
                HorizontalDivider(color = Panel)
                Text(
                    "${usable.size - 1} more offers",
                    color = Lime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { showAll = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PayRow(option: FoodPayOption, selected: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            if (option.iconUrl != null) {
                AsyncImage(model = option.iconUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
            } else {
                Text("₹", color = Ink, fontWeight = FontWeight.Black)
            }
        }
        Text(option.label, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
        Box(
            Modifier.size(20.dp).border(2.dp, if (selected) Lime else Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(10.dp).background(Lime, CircleShape)) }
    }
}

// ---- Order result ------------------------------------------------------------

private const val REMEMBER_DISH_LIMIT = 3

/** Ask, don't assume: one quiet offer per ordered dish, and nothing is saved without the tap. */
@Composable
private fun RememberDishes(dishes: List<String>, actions: FoodActions) {
    val saved = remember(dishes) { mutableStateListOf<String>() }
    dishes.distinct().take(REMEMBER_DISH_LIMIT).forEach { dish ->
        if (dish in saved) {
            Text("I'll remember that you like $dish.", color = Ink.copy(alpha = 0.75f), fontSize = 13.sp)
        } else {
            TextAction("Remember that you like $dish?", onClick = {
                actions.rememberDish(dish)
                saved += dish
            }, color = Ink)
        }
    }
}

@Composable
private fun OrderCard(card: FoodCard.Order, actions: FoodActions) {
    if (card.placed) {
        Surface(color = Lime, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✓", color = Lime, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Text("Ordered.\nYou stayed in control.", color = Ink, fontSize = 30.sp, fontFamily = FontFamily.Serif, lineHeight = 34.sp)
                Text(card.detail, color = Ink.copy(alpha = 0.75f), fontSize = 13.sp)
                card.orderId?.let { Text("${orderingProvider.name} order $it", color = Ink.copy(alpha = 0.6f), fontSize = 11.sp) }
                Button(
                    onClick = { actions.track(card.orderId) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
                ) { Text("Track order", fontWeight = FontWeight.Bold) }
                RememberDishes(card.dishes, actions)
            }
        }
        return
    }
    Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
                    Text("₹", color = Color.White, fontWeight = FontWeight.Black)
                }
                Text(card.title, color = Paper, fontSize = 20.sp, fontFamily = FontFamily.Serif, modifier = Modifier.padding(start = 12.dp))
            }
            Text(card.detail, color = Muted, fontSize = 13.sp)
            card.orderId?.let { Text("Order $it", color = Muted, fontSize = 11.sp) }
            val pending = card.pending ?: return@Column
            pending.upiLink?.let { link ->
                Button(
                    onClick = { actions.openUpi(link) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                ) { Text("Open UPI app to pay", fontWeight = FontWeight.Bold) }
            }
            OutlinedButton(
                onClick = { actions.checkPayment(pending) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
            ) { Text("I've paid — check status", color = Lime) }
        }
    }
}


// ---- The same shop on Zomato ---------------------------------------------------

/** Zomato's answer for one shop while it loads: [shop] is null until it arrives, and stays null without Zomato. */
internal data class ZomatoLookup(val loading: Boolean, val shop: ZomatoShop?)

/**
 * Looks up each Swiggy shop on Zomato in parallel, after the Swiggy card is already on screen, so the
 * comparison never slows down the first answer.
 */
@Composable
private fun rememberZomatoShops(address: FoodAddress, restaurants: List<FoodRestaurant>, actions: FoodActions): Map<String, ZomatoLookup> {
    val shops = remember(address.id) { mutableStateMapOf<String, ZomatoLookup>() }
    val ids = restaurants.map { it.id }.distinct()
    // Keyed on every shop shown, not on the ones still missing: marking a shop as loading must not restart
    // (and so cancel) the very lookup that is filling it in.
    LaunchedEffect(address.id, ids) {
        // Anything not finished is looked up again: "See N more" restarts this effect, which cancels lookups
        // in flight, and a cancelled one must not stay on "Checking Zomato…" forever.
        val wanted = restaurants.distinctBy { it.id }.filter { shops[it.id]?.loading != false }
        wanted.forEach { shops[it.id] = ZomatoLookup(loading = true, shop = null) }
        coroutineScope {
            wanted.forEach { restaurant ->
                launch {
                    try {
                        val shop = runCatching { actions.zomatoShop(address, restaurant) }
                            .getOrElse { if (it is CancellationException) throw it else ZomatoShop(restaurant.name, emptyList(), NO_ANSWER) }
                        shops[restaurant.id] = ZomatoLookup(loading = false, shop = shop)
                    } finally {
                        if (shops[restaurant.id]?.loading == true) shops.remove(restaurant.id)
                    }
                }
            }
        }
    }
    return shops
}

/** Swiggy's price as a number, decimals kept: "₹199.50" is 199.5, not 199. */
internal fun priceRupees(price: String): Double? = Regex("""\d+(\.\d+)?""").find(price.replace(",", ""))?.value?.toDoubleOrNull()

/** ₹ amount without a trailing ".0": 249.0 shows as 249, 249.5 as 249.5. */
internal fun rupeeText(rupees: Double) = if (rupees % 1.0 == 0.0) rupees.toLong().toString() else "%.2f".format(rupees).trimEnd('0')

/**
 * The same shop's Zomato answer as one dish price, for the shared price line: still loading is null, a failed
 * lookup is left out, and only an exact dish match carries a price.
 */
internal fun shopDishPrice(lookup: ZomatoLookup, swiggyName: String): DishPrice? {
    val shop = lookup.shop
    return when {
        lookup.loading -> null
        shop == null -> DishPrice.Failed("not connected")
        shop.gap == NO_ANSWER -> DishPrice.Failed(shop.gap)
        shop.gap != null -> DishPrice.Gap(shop.gap)
        else -> shop.sameAs(swiggyName)?.let { DishPrice.Found(it.price * PAISE_IN_RUPEE) } ?: DishPrice.Missing
    }
}

private const val PAISE_IN_RUPEE = 100L

/** Above a Swiggy menu: the same shop's dishes on Zomato, so a different biryani or a better price is not missed. */
@Composable
private fun ZomatoShopBlock(lookup: ZomatoLookup?, swiggyNames: List<String>) {
    val shop = lookup?.shop ?: return
    if (shop.gap != null || shop.dishes.isEmpty()) return
    val onlyOnZomato = shop.dishes.filter { dish -> swiggyNames.none { dishKey(it) == dishKey(dish.name) } }
    Surface(color = CardInk, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Same shop on Zomato · ${shop.dishes.size} dishes", color = ZomatoRed)
            Text("From ₹${shop.dishes.first().price}. Each dish below shows its Zomato price.", color = Muted, fontSize = 12.sp)
            if (onlyOnZomato.isNotEmpty()) {
                Text(
                    "Only on Zomato: " + onlyOnZomato.take(3).joinToString { "${it.name} ₹${it.price}" },
                    color = Paper,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Zomato's red, for its prices beside Swiggy's: a label colour, never the only signal (the text says which is cheaper). */
private val ZomatoRed = Color(0xFFF0626E)


/**
 * The dish across Zomato, below Swiggy's list, the way groceries show Zepto beside Instamart: Zomato's shops
 * with their matching dishes and prices, and which app has it cheapest. Zomato orders open in its app.
 */
@Composable
private fun ZomatoNearbyBlock(card: FoodCard.Dishes, actions: FoodActions) {
    val context = LocalContext.current
    var loading by remember(card.address.id, card.query) { mutableStateOf(true) }
    var nearby by remember(card.address.id, card.query) { mutableStateOf<ZomatoNearby?>(null) }
    LaunchedEffect(card.address.id, card.query) {
        nearby = runCatching { actions.zomatoNearby(card.address) }
            .getOrElse { if (it is CancellationException) throw it else ZomatoNearby(emptyList(), NO_ANSWER) }
        loading = false
    }
    val result = nearby
    if (!loading && result == null) return // Zomato isn't connected: Swiggy alone, as before.
    val zomatoMin = result?.shops?.minOfOrNull { it.dishes.first().price }
    // Only the same dish is compared across apps: "Chicken Biryani" against "Chicken Biryani", never against "Egg Biryani".
    val swiggyByDish = card.dishes.mapNotNull { dish ->
        priceRupees(dish.item.chosenSize?.price ?: dish.item.price)?.let { dishKey(dish.item.name) to it }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, prices) -> prices.min() }
    val sameDish = result?.shops.orEmpty().flatMap { it.dishes }
        .mapNotNull { dish -> swiggyByDish[dishKey(dish.name)]?.let { Triple(dish.name.substringBefore(" · "), dish.price, it) } }
        .minByOrNull { it.second }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceLabel(
            "Zomato · live" + (zomatoMin?.let { " · cheapest of the ${result.checked} we checked: ₹$it" } ?: ""),
            color = ZomatoRed,
        )
        if (sameDish != null) {
            val (name, zomato, swiggy) = sameDish
            val swiggyText = rupeeText(swiggy)
            Text(
                when {
                    zomato < swiggy -> "$name: ₹$zomato on Zomato, ₹$swiggyText on Swiggy"
                    zomato > swiggy -> "$name: ₹$swiggyText on Swiggy, ₹$zomato on Zomato"
                    else -> "$name: ₹$zomato on both"
                },
                color = Paper,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when {
            loading -> Text("Checking Zomato for ${card.query}…", color = Muted, fontSize = 13.sp)
            result?.gap != null -> Text(result.gap, color = Muted, fontSize = 13.sp)
            else -> Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    result?.shops?.forEachIndexed { index, shop ->
                        if (index > 0) HorizontalDivider(color = Panel)
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shop.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (shop.rating.isNotBlank()) RatingBadge(shop.rating)
                            }
                            if (shop.eta.isNotBlank()) Text(shop.eta, color = Muted, fontSize = 12.sp)
                            shop.dishes.take(ZOMATO_DISHES_PER_SHOP).forEach { dish ->
                                Row {
                                    Text(dish.name, color = Paper, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("₹${dish.price}", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result?.shops?.isNotEmpty() == true) {
            TextAction("Order these on Zomato", onClick = {
                val app = context.packageManager.getLaunchIntentForPackage(ZOMATO_PACKAGE)
                runCatching { context.startActivity(app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zomato.com"))) }
            }, color = ZomatoRed)
        }
    }
}

private const val ZOMATO_DISHES_PER_SHOP = 3
