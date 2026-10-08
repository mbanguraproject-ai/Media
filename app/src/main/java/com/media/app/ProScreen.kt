package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ============================================================================
//  THE PRO PAGE
//
//  One screen that says plainly what Free has, what Pro adds and what it
//  costs. The price is Play's, in the buyer's own currency, never a number
//  written here. One payment, no subscription; Restore asks Play again, for
//  a new phone or a reinstall. Owners see the same list with everything
//  ticked and a thank-you in place of the button.
// ============================================================================

private val ProGold = Color(0xFFF6C344)
private val ProGoldDeep = Color(0xFFD38A12)
private val OnGold = Color(0xFF241A04)

/** One line of the comparison: what it is, and whether Free has it. */
private class PlanRow(val text: Int, val free: Boolean)

private val PlanRows = listOf(
    PlanRow(R.string.pro_row_library, free = true),
    PlanRow(R.string.pro_row_sound, free = true),
    PlanRow(R.string.pro_row_ads, free = false),
    PlanRow(R.string.pro_row_display, free = false),
    PlanRow(R.string.pro_row_loop, free = false),
    PlanRow(R.string.pro_row_speed, free = false),
    PlanRow(R.string.pro_row_silence, free = false),
    PlanRow(R.string.pro_row_resume, free = false),
    PlanRow(R.string.pro_row_soft, free = false),
    PlanRow(R.string.pro_row_mono, free = false),
    PlanRow(R.string.pro_row_crown, free = false)
)

@Composable
fun ProScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val pro = isPro()
    val product by Billing.product.collectAsState()
    val price = product?.oneTimePurchaseOfferDetails?.formattedPrice

    Column(
        Modifier.fillMaxSize().screenBackground().statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.sm, Space.sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream)
            }
        }

        // Hero
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProCrown(size = 72.dp, lit = true)
            Spacer(Modifier.height(Space.md))
            Text(
                stringResource(if (pro) R.string.pro_active_title else R.string.pro_title),
                style = Typo.Display, color = MediaColors.Cream, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                stringResource(if (pro) R.string.pro_active_body else R.string.pro_tagline),
                style = Typo.Body, color = MediaColors.CreamDim, textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(Space.xl))

        // The comparison
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl)
                .clip(RoundedCornerShape(Radius.lg))
                .background(MediaColors.FillSubtle)
                .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.lg))
                .padding(vertical = Space.sm)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.pro_plan_free), style = Typo.Label, color = MediaColors.CreamDim,
                    textAlign = TextAlign.Center, modifier = Modifier.width(PlanColumn))
                Box(Modifier.width(PlanColumn), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.pro_plan_pro),
                        style = Typo.Label.copy(fontWeight = FontWeight.Bold), color = OnGold,
                        modifier = Modifier.clip(RoundedCornerShape(Radius.pill))
                            .background(Brush.horizontalGradient(listOf(ProGold, ProGoldDeep)))
                            .padding(horizontal = Space.md, vertical = 3.dp)
                    )
                }
            }
            PlanRows.forEach { row -> PlanLine(stringResource(row.text), row.free) }
        }

        Spacer(Modifier.height(Space.xl))

        if (pro) {
            Text(
                stringResource(R.string.pro_thanks),
                style = Typo.Secondary, color = MediaColors.CreamDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl)
            )
        } else {
            // The button. Disabled until Play has said what it costs.
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Space.xl)
                    .height(56.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (price != null) Brush.horizontalGradient(listOf(ProGold, ProGoldDeep))
                        else Brush.horizontalGradient(listOf(MediaColors.Fill, MediaColors.Fill))
                    )
                    .then(
                        if (price != null) Modifier.pressScale(haptic = true) {
                            (context as? android.app.Activity)?.let { Billing.purchase(it) }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (price != null) stringResource(R.string.pro_buy, price) else stringResource(R.string.pro_price_loading),
                    style = Typo.Primary.copy(fontWeight = FontWeight.Bold),
                    color = if (price != null) OnGold else MediaColors.CreamDim
                )
            }
            Spacer(Modifier.height(Space.sm))
            Text(
                stringResource(R.string.pro_once),
                style = Typo.Tertiary, color = MediaColors.CreamFaint, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl)
            )
        }

        Spacer(Modifier.height(Space.md))
        Text(
            stringResource(R.string.pro_restore),
            style = Typo.Label, color = MediaColors.Accent, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .clickable { Billing.restoreNow() }
                .padding(vertical = Space.md)
        )
        Spacer(Modifier.height(Space.xl))
    }
}

private val PlanColumn = 56.dp

@Composable
private fun PlanLine(text: String, free: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = Typo.Secondary, color = MediaColors.Cream, modifier = Modifier.weight(1f))
        Box(Modifier.width(PlanColumn), contentAlignment = Alignment.Center) {
            if (free) Icon(Icons.Rounded.Check, null, tint = MediaColors.CreamDim, modifier = Modifier.size(18.dp))
            else Icon(Icons.Rounded.Remove, null, tint = MediaColors.CreamFaint, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.width(PlanColumn), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = ProGold, modifier = Modifier.size(20.dp))
        }
    }
}
