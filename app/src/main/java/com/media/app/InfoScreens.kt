package com.media.app
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
private fun InfoScaffold(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(Space.xl, Space.sm, Space.xl, 40.dp),
            content = content
        )
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim,
        modifier = Modifier.padding(bottom = Space.md))
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream,
        modifier = Modifier.padding(top = Space.md, bottom = Space.sm))
}

@Composable
fun AboutScreen(version: String, onClose: () -> Unit) {
    InfoScaffold(stringResource(R.string.about_title), onClose) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
            color = MediaColors.Cream, modifier = Modifier.padding(bottom = Space.xs))
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge,
            color = MediaColors.CreamDim, modifier = Modifier.padding(bottom = Space.xl))

        Para(stringResource(R.string.about_intro))

        Heading(stringResource(R.string.about_h1))
        Para(stringResource(R.string.about_p1))

        Heading(stringResource(R.string.about_h2))
        Para(stringResource(R.string.about_p2))

        Heading(stringResource(R.string.about_h3))
        Para(stringResource(R.string.about_p3))

        Heading(stringResource(R.string.about_h4))
        Para(stringResource(R.string.about_p4))

        Heading(stringResource(R.string.about_h5))
        Para(stringResource(R.string.about_p5))

        Heading(stringResource(R.string.about_h6))
        Para(stringResource(R.string.about_p6))

        Spacer(Modifier.height(Space.lg))
        Text(stringResource(R.string.version_value, version), style = MaterialTheme.typography.bodyMedium,
            color = MediaColors.CreamFaint)
        Spacer(Modifier.height(Space.sm))
        Text(stringResource(R.string.about_made_with_care),
            style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamFaint)
    }
}

@Composable
fun TermsScreen(onClose: () -> Unit) {
    InfoScaffold(stringResource(R.string.terms_title), onClose) {
        // The terms themselves stay in English, the version that governs: a
        // machine-made translation of legal text can change what it says.
        // Every other language says so here; in English this is empty.
        stringResource(R.string.terms_english_note).takeIf { it.isNotBlank() }?.let { Para(it) }
        Para(
            "These terms cover your use of Aura. They are written to be read, " +
            "not to be got past."
        )

        Heading("What Aura is")
        Para(
            "Aura is a media player for files that are already on your device. " +
            "It reads your storage through Android's media library, plays what " +
            "it finds, and lets you organise it. It is not a streaming service " +
            "and it has no catalogue of its own."
        )

        Heading("Your files stay yours")
        Para(
            "Aura does not upload your media anywhere. Playlists, favourites, " +
            "corrected titles and artwork choices are stored on this device " +
            "only, and are removed when you uninstall the app. Aura claims no " +
            "ownership of anything you play through it."
        )

        Heading("Online artwork and lyrics")
        Para(
            "If you turn on online lookups in Settings, or ask for a cover or " +
            "lyrics by hand, Aura sends that track's title, artist, album and " +
            "length to MusicBrainz, Cover Art Archive, Deezer, Apple's iTunes " +
            "Search, LRCLIB, NetEase and lyrics.ovh, and saves " +
            "what comes back on this device. Nothing else about you or your " +
            "library is sent, and nothing is written into your files. Those " +
            "services' own terms apply to what they receive."
        )

        Heading("Aura Share and your network")
        Para(
            "When you send something to a TV or speaker, your phone serves that " +
            "one file over your own Wi-Fi to the device you picked, for as long " +
            "as it is playing. Nothing leaves your network and nothing is " +
            "uploaded to us or to anyone else. Anyone already on that network " +
            "could reach that file while it is being shared, so treat it as you " +
            "would anything else on your home Wi-Fi."
        )

        Heading("Permissions, and why")
        Para(
            "Audio and video access is what lets Aura see your library at all. " +
            "Notifications carry the playback controls. Network and Wi-Fi state " +
            "are used by Aura Share to find devices on your local network and " +
            "by advertising. Aura asks for nothing it does not use."
        )

        Heading("Advertising")
        Para(
            "The free version shows ads supplied by Google AdMob. Google may " +
            "use your device's advertising identifier to choose them. In the " +
            "UK and the EEA you are asked for consent the first time the app " +
            "runs; if you decline, ads still appear but are not personalised. " +
            "You can change or withdraw that choice at any time in Settings, " +
            "Privacy, Ad privacy choices, which in the US states that require " +
            "it also lets you opt out of the sale or sharing of your data. " +
            "You can reset or delete the advertising identifier at any time in " +
            "Android's settings. Google's own privacy terms govern what it " +
            "collects, and the privacy policy linked in Settings says more."
        )

        Heading("Removing ads")
        Para(
            "Ad-free is a one-time purchase made through Google Play. Google " +
            "takes the payment and holds the receipt; Aura only asks Play " +
            "whether you own it. Refunds are handled by Google under Play's " +
            "own policy, not by us, and the purchase is tied to your Google " +
            "account rather than to this phone."
        )

        Heading("Deleting files")
        Para(
            "Aura can delete a file from your device when you ask it to. " +
            "Deletion is permanent, is confirmed by Android itself on modern " +
            "versions, and cannot be undone from inside the app."
        )

        Heading("Acceptable use")
        Para(
            "Use Aura with content you have the right to play. What you put on " +
            "your device is your responsibility, and Aura neither checks nor " +
            "polices it."
        )

        Heading("No warranty")
        Para(
            "Aura is provided as-is, without warranty of any kind. It is a " +
            "player: it reads your files and does not modify them, but you " +
            "should keep backups of anything you cannot replace, as you would " +
            "with any software."
        )

        Heading("Liability")
        Para(
            "To the extent the law allows, the developer is not liable for loss " +
            "or damage arising from use of the app. Nothing here removes rights " +
            "you have under the consumer law where you live."
        )

        Heading("Changes")
        Para(
            "These terms may change as the app does. The current version is " +
            "always the one in this screen, and continued use after a change " +
            "means you accept it."
        )

        Heading("Contact")
        Para(
            "Questions, and anything you think is wrong here, go to the contact " +
            "address on mebs.app."
        )
    }
}
