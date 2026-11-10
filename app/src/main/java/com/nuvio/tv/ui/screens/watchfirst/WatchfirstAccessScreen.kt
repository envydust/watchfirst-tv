@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.watchfirst

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.watchfirst.WatchfirstAccess
import com.nuvio.tv.core.watchfirst.WatchfirstAccessException
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val ErrorText = Color(0xFFFF9AA9)
private val NoticeAmber = Color(0xFFFFC857)
private val EmailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/**
 * WATCHFIRST entry for the TV, the same two steps as the PC and phone: name and e-mail, then the code the
 * member generates on their plan's site. Built for the remote: one column of large targets, focus starts on the
 * first field and Enter moves down.
 */
@Composable
fun WatchfirstAccessScreen(
    access: WatchfirstAccess,
    notice: String?,
    onConnected: () -> Unit,
) {
    val colors = NuvioTheme.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf(access.savedName) }
    var email by rememberSaveable { mutableStateOf(access.savedEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeStep by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val firstField = remember { FocusRequester() }
    val codeField = remember { FocusRequester() }

    LaunchedEffect(codeStep) {
        runCatching { if (codeStep) codeField.requestFocus() else firstField.requestFocus() }
    }

    fun continueToCode() {
        error = when {
            name.trim().length < 2 -> "Digite seu nome."
            !EmailPattern.matches(email.trim()) -> "Digite um e-mail válido."
            else -> ""
        }
        if (error.isEmpty()) codeStep = true
    }

    fun connect() {
        if (busy) return
        if (code.trim().length < 6) {
            error = "Digite o código gerado no site."
            return
        }
        busy = true
        error = ""
        scope.launch {
            try {
                access.connect(name, email, code)
                onConnected()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: WatchfirstAccessException) {
                error = failure.message.orEmpty()
            } catch (failure: Exception) {
                error = failure.message?.takeIf { it.isNotBlank() } ?: "Não foi possível conectar. Tente novamente."
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
            .background(
                Brush.radialGradient(
                    colors = listOf(colors.Secondary.copy(alpha = 0.10f), Color.Transparent),
                    radius = 1400f,
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: brand and the three steps, so the member knows where the code comes from.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo_wordmark),
                    contentDescription = "WATCHFIRST",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(40.dp).width(240.dp),
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    text = if (codeStep) "Digite o código do site" else "Sua próxima sessão começa aqui.",
                    color = colors.TextPrimary,
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Filmes, séries e TV ao vivo do seu plano WATCHFIRST.",
                    color = colors.TextSecondary,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                )
                Spacer(Modifier.height(22.dp))
                StepLine(1, "Informe seu nome e e-mail.", active = !codeStep)
                StepLine(2, "No celular ou computador, abra o site do seu plano e toque em \"Gerar código para conectar\".", active = codeStep)
                StepLine(3, "Digite aqui o código que aparecer.", active = codeStep)
            }

            // Right: the form.
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.BackgroundElevated)
                    .border(1.dp, Color.White.copy(alpha = 0.065f), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!notice.isNullOrBlank() && !codeStep) {
                    Text(text = notice, color = NoticeAmber, fontSize = 15.sp)
                }
                if (!codeStep) {
                    FieldLabel("Seu nome")
                    InputField(
                        value = name,
                        onValueChange = { name = it.take(60); error = "" },
                        placeholder = "Como você quer ser chamado",
                        imeAction = ImeAction.Next,
                        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField),
                    )
                    FieldLabel("Seu e-mail")
                    InputField(
                        value = email,
                        onValueChange = { email = it.take(120); error = "" },
                        placeholder = "voce@exemplo.com",
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ErrorLine(error)
                    PrimaryButton(text = "Continuar", onClick = ::continueToCode)
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.BackgroundCard)
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name.trim(), color = colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(email.trim(), color = colors.TextSecondary, fontSize = 14.sp)
                        }
                        Button(
                            onClick = { codeStep = false; error = "" },
                            enabled = !busy,
                            colors = ButtonDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = colors.Secondary,
                                focusedContainerColor = colors.FocusBackground,
                                focusedContentColor = colors.Secondary,
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                        ) { Text("Alterar") }
                    }
                    FieldLabel("Código")
                    InputField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(51); error = "" },
                        placeholder = "WF-XXXX-XXXX",
                        imeAction = ImeAction.Done,
                        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        modifier = Modifier.fillMaxWidth().focusRequester(codeField),
                    )
                    ErrorLine(error)
                    PrimaryButton(text = if (busy) "Conectando…" else "Entrar", enabled = !busy, onClick = ::connect)
                    Text(
                        text = "O código vale 10 minutos e funciona uma vez. Ele libera somente esta TV.",
                        color = colors.TextTertiary,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = "O acesso exige conexão com a internet.",
                    color = colors.TextTertiary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ErrorLine(text: String) {
    if (text.isNotBlank()) Text(text = text, color = ErrorText, fontSize = 14.sp)
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.colors(
            containerColor = colors.Secondary,
            contentColor = colors.OnSecondary,
            focusedContainerColor = Color.White,
            focusedContentColor = colors.OnSecondary,
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StepLine(number: Int, text: String, active: Boolean) {
    val colors = NuvioTheme.colors
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (active) colors.Secondary else colors.BackgroundCard),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                color = if (active) colors.OnSecondary else colors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            color = if (active) colors.TextPrimary else colors.TextSecondary,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
