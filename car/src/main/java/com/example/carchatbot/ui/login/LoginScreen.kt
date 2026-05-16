package com.example.carchatbot.ui.login

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carchatbot.R
import kotlinx.coroutines.delay

object LoginColors {
    val background = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val surfaceVariant = Color(0xFF1C2128)
    val surfaceCard = Color(0xFF21262D)
    val primary = Color(0xFF4CAF50)
    val primaryDark = Color(0xFF2E7D32)
    val textPrimary = Color(0xFFE6EDF3)
    val textSecondary = Color(0xFF8B949E)
    val border = Color(0xFF30363D)
    val borderLight = Color(0xFF3D444D)
    val error = Color(0xFFEF5350)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    loginViewModel: LoginViewModel,
    onLoginSuccess: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val loginState by loginViewModel.loginState.collectAsState()
    val phoneFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(loginState) {
        if (loginState is LoginState.Success) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(Unit) {
        delay(300)
        phoneFocusRequester.requestFocus()
        keyboardController?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        LoginColors.background,
                        Color(0xFF0A0E13)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoginColors.textPrimary,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Đăng nhập để tiếp tục",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = LoginColors.textSecondary,
                    letterSpacing = 0.3.sp
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black.copy(alpha = 0.5f)
                    ),
                shape = RoundedCornerShape(24.dp),
                color = LoginColors.surfaceCard,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = LoginColors.borderLight.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(28.dp)
                ) {
                    PremiumLoginTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = "Số điện thoại",
                        icon = Icons.Default.Person,
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        focusRequester = phoneFocusRequester
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    PremiumPasswordTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Mật khẩu",
                        icon = Icons.Default.Lock,
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (
                                    loginState != LoginState.Loading &&
                                    phoneNumber.isNotBlank() &&
                                    password.isNotBlank()
                                ) {
                                    keyboardController?.hide()
                                    loginViewModel.login(phoneNumber, password)
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    PremiumLoginButton(
                        text = "Đăng nhập",
                        onClick = {
                            loginViewModel.login(phoneNumber, password)
                        },
                        enabled = loginState != LoginState.Loading &&
                                phoneNumber.isNotBlank() &&
                                password.isNotBlank(),
                        isLoading = loginState == LoginState.Loading
                    )

                    val isDemoConsumed by loginViewModel.isDemoConsumed.collectAsState()
                    if (!isDemoConsumed) {
                         Spacer(modifier = Modifier.height(16.dp))
                         OutlinedButton(
                             onClick = { loginViewModel.startDemo() },
                             modifier = Modifier
                                 .fillMaxWidth()
                                 .height(56.dp),
                             shape = RoundedCornerShape(14.dp),
                             border = androidx.compose.foundation.BorderStroke(1.dp, LoginColors.textSecondary.copy(alpha = 0.5f)),
                             colors = ButtonDefaults.outlinedButtonColors(
                                 contentColor = LoginColors.textPrimary
                             ),
                             enabled = loginState != LoginState.Loading
                         ) {
                             if (loginState == LoginState.Loading) {
                             } else {
                                 Text(
                                     text = "Dùng thử 1 giờ",
                                     fontSize = 16.sp,
                                     fontWeight = FontWeight.SemiBold
                                 )
                             }
                         }
                    }

                    AnimatedVisibility(
                        visible = loginState is LoginState.Error,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        if (loginState is LoginState.Error) {
                            Spacer(modifier = Modifier.height(16.dp))
                            ErrorMessage((loginState as LoginState.Error).message)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumLoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null
) {
    Column {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = LoginColors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                ),
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LoginColors.textSecondary
                )
            },
            colors = TextFieldDefaults.textFieldColors(
                containerColor = LoginColors.surface,
                focusedTextColor = LoginColors.textPrimary,
                unfocusedTextColor = LoginColors.textPrimary,
                cursorColor = LoginColors.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = LoginColors.primary,
                unfocusedLeadingIconColor = LoginColors.textSecondary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = LoginColors.textPrimary
            ),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = keyboardActions
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumPasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Column {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = LoginColors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)),
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LoginColors.textSecondary
                )
            },
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector = if (passwordVisible)
                            Icons.Default.Done
                        else
                            Icons.Default.Close,
                        contentDescription = if (passwordVisible) "Ẩn mật khẩu" else "Hiện mật khẩu",
                        tint = LoginColors.textSecondary
                    )
                }
            },
            visualTransformation = if (passwordVisible)
                VisualTransformation.None
            else
                PasswordVisualTransformation(),
            colors = TextFieldDefaults.textFieldColors(
                containerColor = LoginColors.surface,
                focusedTextColor = LoginColors.textPrimary,
                unfocusedTextColor = LoginColors.textPrimary,
                cursorColor = LoginColors.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = LoginColors.primary,
                unfocusedLeadingIconColor = LoginColors.textSecondary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = LoginColors.textPrimary
            ),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = keyboardActions
        )
    }
}

@Composable
fun PremiumLoginButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isLoading: Boolean
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = if (enabled && !isLoading) 12.dp else 0.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = if (enabled && !isLoading) LoginColors.primary.copy(alpha = 0.4f) else Color.Transparent,
                spotColor = if (enabled && !isLoading) LoginColors.primary.copy(alpha = 0.4f) else Color.Transparent
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = LoginColors.primary,
            contentColor = Color.White,
            disabledContainerColor = LoginColors.surface,
            disabledContentColor = LoginColors.textSecondary
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun ErrorMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = LoginColors.error.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            LoginColors.error.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = LoginColors.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = LoginColors.error
            )
        }
    }
}
