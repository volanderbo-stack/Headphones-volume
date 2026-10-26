package com.headphones.volume.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.headphones.volume.audio.ExtraQuiet
import com.headphones.volume.audio.VolumeController
import com.headphones.volume.core.VolumeDirection
import com.headphones.volume.core.Diagnostics
import com.headphones.volume.core.KeyRouter
import com.headphones.volume.core.PermissionState
import com.headphones.volume.core.Permissions
import com.headphones.volume.service.MediaNotificationListener
import com.headphones.volume.settings.AppSettings
import com.headphones.volume.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private typealias Update = ((AppSettings) -> AppSettings) -> Unit

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val connected by Diagnostics.accessibilityConnected.collectAsStateWithLifecycle()
    val compatRunning by Diagnostics.compatRunning.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf(Permissions.read(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        perms = Permissions.read(context)
        KeyRouter.syncCompatService()
    }
    // Тест не має блокувати перемикання треків, коли користувач пішов з екрана.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { Diagnostics.testMode.value = false }

    val update: Update = { transform -> scope.launch { repo.update(transform) } }

    Scaffold { padding ->
        val s = settings
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Гучність з навушників",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            MainSwitchCard(s, connected, update)
            SetupCard(perms)
            VolumeCard(s, update)
            TimingCard(s, update)
            OptionsCard(s, update)
            BluetoothCard(s, perms, compatRunning, update)
            TestCard(listening = connected || compatRunning)
        }
    }
}

@Composable
private fun MainSwitchCard(s: AppSettings, connected: Boolean, update: Update) {
    Section {
        SwitchRow(
            title = if (s.enabled) "Увімкнено" else "Вимкнено",
            checked = s.enabled,
            big = true,
        ) { value -> update { it.copy(enabled = value) } }
        val louder = if (s.swapButtons) "⏮" else "⏭"
        val quieter = if (s.swapButtons) "⏭" else "⏮"
        val status = when {
            !s.enabled -> "Кнопки навушників працюють як звичайно."
            !connected -> "⚠️ Сервіс ще не підключено. Виконайте крок 1 нижче."
            s.swapFunctions -> "✅ Працює. Натисніть $louder, щоб було гучніше, або $quieter, щоб тихіше. " +
                "Затисніть кнопку, щоб перемкнути трек."
            else -> "✅ Працює. Затисніть $louder, щоб було гучніше, або $quieter, щоб тихіше. " +
                "Коротке натискання перемикає трек."
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SetupCard(perms: PermissionState) {
    val context = LocalContext.current
    Section("Налаштування дозволів") {
        Step(
            number = 1,
            title = "Спеціальні можливості",
            done = perms.accessibility,
            description = "Знайдіть у списку «Гучність з навушників» (іноді в розділі «Завантажені " +
                "застосунки») і ввімкніть. Так застосунок бачить кнопки навушників.",
            button = "Відкрити Спеціальні можливості",
        ) { context.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        if (perms.mayNeedRestrictedSettings) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Перемикач сірий або пише «Обмежене налаштування»?", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Це захист Android 13+ для застосунків не з Play Маркету. Спершу спробуйте " +
                            "ввімкнути сервіс (крок 1), потім відкрийте «Про застосунок» → ⋮ угорі праворуч → " +
                            "«Дозволити обмежені налаштування» і поверніться до кроку 1.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            context.openSettings(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Відкрити «Про застосунок»") }
                }
            }
        }

        Step(
            number = 2,
            title = "Доступ до сповіщень",
            done = perms.notificationAccess,
            description = "Потрібен, щоб коротке натискання перемикало трек саме у вашому плеєрі. " +
                "Застосунок не читає ваші сповіщення.",
            button = "Відкрити доступ до сповіщень",
        ) { context.openNotificationAccess() }

        Step(
            number = 3,
            title = "Не обмежувати роботу у фоні",
            done = perms.batteryUnrestricted,
            description = "Щоб Android не вимикав застосунок. На Xiaomi також увімкніть «Автозапуск», " +
                "на Samsung: Батарея → «Без обмежень», на Huawei: «Запуск застосунків» → вручну.",
            button = "Дозволити роботу у фоні",
        ) { context.requestBatteryExemption() }
    }
}

@Composable
private fun VolumeCard(s: AppSettings, update: Update) {
    val context = LocalContext.current
    val volume = remember { VolumeController(context) }
    var level by remember { mutableIntStateOf(volume.current) }
    val extraQuietActive by ExtraQuiet.active.collectAsStateWithLifecycle()
    val extraQuietSupported by ExtraQuiet.supported.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                level = volume.current
                delay(200)
            }
        }
    }
    val max = volume.max.coerceAtLeast(1)
    Section("Поточна гучність") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (extraQuietActive) "½" else "$level",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(" з $max", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (extraQuietActive) {
            Text(
                "Гучність $level, приглушена ще на ${s.extraQuietDb} дБ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { (if (extraQuietActive) level - 0.5f else level.toFloat()) / max },
            modifier = Modifier.fillMaxWidth().height(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val buttonModifier = Modifier.weight(1f).heightIn(min = 56.dp)
            OutlinedButton(
                onClick = { KeyRouter.stepVolumeFromUi(VolumeDirection.DOWN) },
                modifier = buttonModifier,
            ) { Text("− Тихіше") }
            OutlinedButton(
                onClick = { KeyRouter.stepVolumeFromUi(VolumeDirection.UP) },
                modifier = buttonModifier,
            ) { Text("+ Гучніше") }
        }
    }

    val lowest = if (s.lowestAudible > 0) s.lowestAudible else volume.autoLowestAudible
    Section("Тихіше за найменшу гучність") {
        IntSlider(
            label = { "Найменша чутна гучність: $it" },
            hint = "Якщо на цій гучності вже нічого не чутно, збільште. Нижчі рівні застосунок пропускає: " +
                "з неї одразу в тишу або в «½».",
            value = lowest,
            range = AppSettings.LOWEST_AUDIBLE_RANGE,
            stepSize = 1,
        ) { value -> update { it.copy(lowestAudible = value) } }
        SwitchRow(
            title = "Рівень «½» нижче найменшої чутної гучності",
            subtitle = "Тихіше: … → ${lowest + 1} → $lowest → ½ → 0. Гучніше з ½ повертає на $lowest.",
            checked = s.extraQuiet,
        ) { value -> update { it.copy(extraQuiet = value) } }
        if (s.extraQuiet) {
            IntSlider(
                label = { "Наскільки тихіше: $it дБ" },
                hint = "6 дБ: трохи тихіше. 10 дБ: приблизно вдвічі тихіше на слух. 20 дБ: ледь чутно.",
                value = s.extraQuietDb,
                range = AppSettings.EXTRA_QUIET_DB_RANGE,
                stepSize = 1,
            ) { value -> update { it.copy(extraQuietDb = value) } }
            if (extraQuietSupported == false) {
                Text(
                    "⚠️ Цей телефон не дозволив приглушити звук аудіоефектом, тому з $lowest гучність одразу йде в 0. " +
                        "Якщо увімкнено інші звукові ефекти (Dolby, еквалайзер), спробуйте їх вимкнути.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TimingCard(s: AppSettings, update: Update) {
    val context = LocalContext.current
    val maxVolume = remember { VolumeController(context).max }
    Section("Швидкість") {
        IntSlider(
            label = { "Час довгого натискання: $it мс" },
            hint = if (s.swapFunctions) {
                "Скільки тримати кнопку, щоб перемкнути трек."
            } else {
                "Менше: спрацьовує швидше, але легше випадково змінити гучність."
            },
            value = s.longPressMs,
            range = AppSettings.LONG_PRESS_RANGE,
            stepSize = 50,
        ) { value -> update { it.copy(longPressMs = value) } }
        IntSlider(
            label = { "Швидкість зміни гучності: $it мс на крок" },
            hint = {
                if (s.swapFunctions) {
                    "Діє лише в режимі «затискання = гучність» (перемикач «Поміняти функції місцями» вимкнено)."
                } else {
                    "Від тиші до максимуму ≈ ${"%.1f".format(maxVolume * it / 1000f)} с. Менше число = швидше."
                }
            },
            value = s.stepMs,
            range = AppSettings.STEP_RANGE,
            stepSize = 10,
        ) { value -> update { it.copy(stepMs = value) } }
    }
}

@Composable
private fun OptionsCard(s: AppSettings, update: Update) {
    Section("Параметри") {
        SwitchRow(
            title = "Поміняти функції місцями",
            subtitle = if (s.swapFunctions) {
                "Зараз: коротке натискання змінює гучність, затискання перемикає трек"
            } else {
                "Зараз: коротке натискання перемикає трек, затискання плавно змінює гучність"
            },
            checked = s.swapFunctions,
        ) { value -> update { it.copy(swapFunctions = value) } }
        if (s.swapFunctions) {
            SwitchRow(
                title = "Затискання навушників перемикає трек (експериментально)",
                subtitle = "Для навушників, які при затисканні самі змінюють гучність. Застосунок повертає " +
                    "гучність і перемикає трек: гучніше означає вперед, тихіше — назад.",
                checked = s.earbudHoldSkips,
            ) { value -> update { it.copy(earbudHoldSkips = value) } }
            if (s.earbudHoldSkips) {
                SwitchRow(
                    title = "Також при увімкненому екрані",
                    subtitle = "Протягом 4 с після дотику до екрана зміна гучності вважається вашою (наприклад, " +
                        "повзунком), і трек не перемикається. Потрібен крок 1 (Спеціальні можливості).",
                    checked = s.earbudHoldScreenOn,
                ) { value -> update { it.copy(earbudHoldScreenOn = value) } }
            }
        }
        SwitchRow(
            title = "Поміняти кнопки місцями",
            subtitle = "Вперед: тихіше, назад: гучніше",
            checked = s.swapButtons,
        ) { value -> update { it.copy(swapButtons = value) } }
        SwitchRow(
            title = "Вібрація при кожному кроці",
            checked = s.vibrate,
        ) { value -> update { it.copy(vibrate = value) } }
        SwitchRow(
            title = "Показувати системну шкалу гучності",
            checked = s.showSystemUi,
        ) { value -> update { it.copy(showSystemUi = value) } }
    }
}

@Composable
private fun BluetoothCard(s: AppSettings, perms: PermissionState, running: Boolean, update: Update) {
    val listening by Diagnostics.compatListening.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Section("Bluetooth-навушники") {
        Text(
            "Спершу перевірте у «Тесті» нижче. Якщо натискання на Bluetooth-навушниках там не з'являються, " +
                "увімкніть режим сумісності: застосунок тихо «грає» тишу, щоб Android надсилав кнопки йому.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SwitchRow(
            title = "Режим сумісності (експериментальний)",
            subtitle = when {
                !perms.notificationAccess -> "Спершу дайте доступ до сповіщень (крок 2)"
                running && listening -> "✅ Навушники підключено: кнопки працюють"
                running -> "Чекає на навушники: увімкнеться сам, щойно їх підключите"
                else -> null
            },
            checked = s.bluetoothCompat,
            enabled = perms.notificationAccess || s.bluetoothCompat,
        ) { value ->
            if (value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            update { it.copy(bluetoothCompat = value) }
        }
    }
}

@Composable
private fun TestCard(listening: Boolean) {
    val testing by Diagnostics.testMode.collectAsStateWithLifecycle()
    val entries by Diagnostics.entries.collectAsStateWithLifecycle()
    Section("Перевірка кнопок") {
        Button(
            onClick = { Diagnostics.testMode.value = !testing },
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        ) { Text(if (testing) "Зупинити тест" else "Тест") }

        if (testing) {
            Text(
                "Натискайте й затискайте кнопки навушників. Трек не перемикається, гучність змінюється.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!listening) {
                Text(
                    "⚠️ Сервіс не підключено, тож тест нічого не покаже. Виконайте крок 1.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (entries.isEmpty()) {
                Text("Чекаю на натискання…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        entries.forEach { entry ->
            Text(
                "${entry.time}  ${entry.text}",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = if (entry.isAction) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (entries.isNotEmpty()) {
            TextButton(onClick = Diagnostics::clear) { Text("Очистити журнал") }
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    description: String,
    button: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${if (done) "✅" else "❌"}  $number. $title",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        if (done) {
            OutlinedButton(onClick = onClick, modifier = modifier) { Text("Готово · відкрити ще раз") }
        } else {
            Button(onClick = onClick, modifier = modifier) { Text(button) }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    big: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun IntSlider(
    label: (Int) -> String,
    hint: (Int) -> String,
    value: Int,
    range: IntRange,
    stepSize: Int,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = current.roundToInt()
    Column {
        Text(label(shown), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / stepSize - 1,
        )
        Text(hint(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IntSlider(
    label: (Int) -> String,
    hint: String,
    value: Int,
    range: IntRange,
    stepSize: Int,
    onCommit: (Int) -> Unit,
) = IntSlider(label, { hint }, value, range, stepSize, onCommit)

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun Context.openNotificationAccess() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, MediaNotificationListener::class.java).flattenToString(),
        )
        try {
            startActivity(detail)
            return
        } catch (e: ActivityNotFoundException) {
            // Деякі прошивки не мають окремого екрана: відкриваємо загальний список.
        }
    }
    openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

@SuppressLint("BatteryLife") // Застосунок не з Play Маркету, а робота у фоні — його суть.
private fun Context.requestBatteryExemption() {
    openSettings(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)),
    )
}
