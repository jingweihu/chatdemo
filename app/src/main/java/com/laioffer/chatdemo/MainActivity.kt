package com.laioffer.chatdemo

/*
 * Chat Demo - Advanced Spacing and Keyboard Handling System
 * 
 * This app implements a sophisticated chat interface with intelligent message positioning
 * and seamless keyboard interactions. Here's how the key systems work:
 * 
 * === SPACING SYSTEM (Anchor-Based Layout) ===
 * 
 * The chat uses an "anchor-based" spacing system instead of simple bottom scrolling:
 * 
 * 1. ANCHOR MESSAGE: The last user message acts as the "anchor" - it's positioned optimally
 *    in the chat area to provide context while leaving space for bot replies.
 * 
 * 2. HEIGHT TRACKING: We dynamically measure message heights using onGloballyPositioned:
 *    - anchorHeightPx: Height of the current anchor (last user message)  
 *    - replyHeights: Map tracking heights of all bot replies after the anchor
 *    - repliesTotalHeightPx: Sum of all reply heights
 * 
 * 3. DYNAMIC SPACER: A bottom spacer is calculated to fill remaining chat area:
 *    spacerPx = chatAreaHeight - anchorHeight - repliesHeight
 *    This pushes the anchor + replies to an optimal position in the viewport.
 * 
 * 4. LAYOUT FLOW: 
 *    [Older messages] → [Current anchor message] → [Bot replies] → [Dynamic spacer]
 * 
 * === KEYBOARD HANDLING SYSTEM ===
 * 
 * Multi-layered approach for smooth keyboard interactions:
 * 
 * 1. WINDOW CONFIGURATION: 
 *    - AndroidManifest.xml: android:windowSoftInputMode="adjustResize"
 *    - Gives us control over layout adjustments instead of auto-pushing
 * 
 * 2. COMPOSE INTEGRATION:
 *    - imePadding() on main Column: Handles keyboard padding automatically
 *    - WindowInsets.ime monitoring: Detects keyboard state changes
 * 
 * 3. SCROLL COORDINATION:
 *    - Only auto-adjusts scroll when user was already at bottom
 *    - Uses instant scrollToItem() for keyboard changes to avoid conflicts
 *    - Preserves user's scroll position when keyboard appears/disappears
 * 
 * 4. USER INTERACTION FLOW:
 *    - Send message: Clear input → Hide keyboard → Short delay → Scroll to bottom
 *    - Manual scroll: Immediately hide keyboard to maximize screen space
 *    - Bot replies: Appear without auto-scroll, letting user control viewing
 * 
 * === WHY THIS DESIGN ===
 * 
 * This system provides several advantages over simple bottom-scrolling:
 * - Better context: Anchor message stays visible when bot replies
 * - Smooth keyboard transitions without jarring layout shifts  
 * - Intelligent scroll behavior that doesn't interrupt user browsing
 * - Optimal space usage in different screen sizes and orientations
 * - Professional chat UX similar to modern messaging apps
 */

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.laioffer.chatdemo.ui.theme.ChatDemoTheme
import kotlinx.coroutines.launch

// Data classes
data class User(
    val id: String,
    val name: String,
    val avatar: Int, // Using drawable resource for avatar
    val isMe: Boolean = false
)

data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val user: User,
    val timestamp: Long = System.currentTimeMillis()
)

val userMe = User(id = "1", name = "Me", avatar = R.drawable.ic_launcher_foreground, isMe = true) // Replace with your actual drawable
val userOther = User(id = "2", name = "Friend", avatar = R.drawable.ic_launcher_background, isMe = false) // Replace with your actual drawable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val messages = remember { mutableStateListOf<Message>() }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Find the anchor message (last user message) - using derivedStateOf to track list changes
    val anchorMessage by remember { 
        derivedStateOf {
            val userMessages = messages.filter { it.user.isMe }
            userMessages.lastOrNull()
        }
    }

    // Separate height tracking for anchor and replies
    var anchorHeightPx by remember { mutableIntStateOf(0) }
    val replyHeights = remember { mutableStateMapOf<String, Int>() }
    var repliesTotalHeightPx by remember { mutableStateOf(0) }
    
    // Reset reply measurements when anchor changes (new user message)
    LaunchedEffect(anchorMessage?.id) {
        replyHeights.clear()
        repliesTotalHeightPx = 0
    }
    
    // Update total reply height when individual reply heights change
    LaunchedEffect(replyHeights.values.toList()) {
        repliesTotalHeightPx = replyHeights.values.sum()
    }

    // Monitor keyboard state and adjust scroll position
    val density = LocalDensity.current
    val imeHeight = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeHeight) {
        // When keyboard appears (imeHeight > 0) or disappears (imeHeight = 0)
        // Ensure we stay at the bottom if we were already there
        if (messages.isNotEmpty() && listState.canScrollForward.not()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Hide keyboard when user starts scrolling
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboardController?.hide()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding() // This handles the keyboard padding
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f)
        ) {
            val density = LocalDensity.current
            val chatAreaHeightPx = with(density) { maxHeight.roundToPx() }

            // Calculate spacer to push anchor message to top (only consider anchor + replies in chat area)
            val spacerPx = if (anchorMessage != null) {
                (chatAreaHeightPx - anchorHeightPx - repliesTotalHeightPx).coerceAtLeast(0)
            } else {
                0
            }
            val spacerDp = with(density) { spacerPx.toDp() }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Top
            ) {
                itemsIndexed(
                    items = messages,
                    key = { _, m -> m.id }
                ) { _, msg ->
                    MessageBubble(
                        message = msg,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            val anchor = anchorMessage
                            if (msg.id == anchor?.id) {
                                // This is the anchor message
                                anchorHeightPx = coordinates.size.height
                            } else if (anchor != null && !msg.user.isMe && msg.timestamp > anchor.timestamp) {
                                // This is a reply after the anchor
                                replyHeights[msg.id] = coordinates.size.height
                            }
                            // Ignore other messages for spacing calculation
                        }
                    )
                }

                // Dynamic bottom spacer fills leftover chat area height
                item(key = "dynamic-bottom-spacer") {
                    Spacer(Modifier.height(spacerDp))
                }
            }
        }

        ChatInput(
            text = inputText,
            onTextChange = { inputText = it },
            onSendClick = {
                if (inputText.text.isNotBlank()) {
                    val messageText = inputText.text.trim()
                    val userMessage = Message(text = messageText, user = userMe)
                    messages.add(userMessage)
                    inputText = TextFieldValue("") // Clear input immediately
                    
                    // Coordinate keyboard hiding and scrolling
                    coroutineScope.launch {
                        keyboardController?.hide()
                        kotlinx.coroutines.delay(100) // Short delay for keyboard animation
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }

                    // Handle responses
                    when (messageText.lowercase()) {
                        "short" -> {
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(500) // Small delay for realistic response
                                val botMessage = Message(
                                    text = "This is a short reply. Just two lines.",
                                    user = userOther
                                )
                                messages.add(botMessage)
                                // Don't auto-scroll for bot replies - let user control scrolling
                            }
                        }
                        "long" -> {
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(500) // Small delay for realistic response
                                val botMessage = Message(
                                    text = "This is a very long message. It's designed to take up more than a page to demonstrate how the scrolling works within the chat application. We need to make sure that the text wraps correctly and that the bubble expands as needed. Let's add some more filler text to ensure it is sufficiently long. This message should continue for several lines, forcing the user to scroll to see the entire content. The quick brown fox jumps over the lazy dog. The five boxing wizards jump quickly. Pack my box with five dozen liquor jugs. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. More text just to be absolutely sure it is long enough. And a bit more. And a final line to make it super long.",
                                    user = userOther
                                )
                                messages.add(botMessage)
                                // Don't auto-scroll for bot replies - let user control scrolling
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val bubbleColor = if (message.user.isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val bubbleShape = if (message.user.isMe) {
        RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.user.isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.user.isMe) {
            AvatarImage(drawableRes = message.user.avatar, description = message.user.name)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f, fill = false) // Important for text to wrap correctly
                .background(bubbleColor, shape = bubbleShape)
                .padding(10.dp)
        ) {
            Text(
                text = message.text,
                color = if (message.user.isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }


        if (message.user.isMe) {
            Spacer(modifier = Modifier.width(8.dp))
            AvatarImage(drawableRes = message.user.avatar, description = message.user.name)
        }
    }
}

@Composable
fun AvatarImage(drawableRes: Int, description: String) {
    Image(
        painter = painterResource(id = drawableRes), // Replace with actual avatar logic if needed
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
    )
}

@Composable
fun ChatInput(
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSendClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") },
            shape = RoundedCornerShape(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = {
            onSendClick()
        }) {
            Icon(Icons.Filled.Send, contentDescription = "Send message")
        }
    }
}

