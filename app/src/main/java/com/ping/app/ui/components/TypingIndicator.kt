// ui/components/TypingIndicator.kt
package com.ping.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ping.app.ui.theme.*

@Composable
fun TypingIndicator(
    userName: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ReceivedBubble)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Animated dots
        repeat(3) { index ->
            AnimatedDot(
                delay = index * 150
            )
        }
        
        userName?.let {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$it is typing",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun AnimatedDot(delay: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 600,
                delayMillis = delay,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 600,
                delayMillis = delay,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )
    
    Box(
        modifier = Modifier
            .size((8 * scale).dp)
            .clip(CircleShape)
            .background(TextSecondary.copy(alpha = alpha))
    )
}

@Composable
fun TypingIndicatorSmall(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            SmallAnimatedDot(delay = index * 100)
        }
    }
}

@Composable
private fun SmallAnimatedDot(delay: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "smallDot")
    
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 400,
                delayMillis = delay,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotOffset"
    )
    
    Box(
        modifier = Modifier
            .offset(y = offsetY.dp)
            .size(4.dp)
            .clip(CircleShape)
            .background(PrimaryPurple)
    )
}
