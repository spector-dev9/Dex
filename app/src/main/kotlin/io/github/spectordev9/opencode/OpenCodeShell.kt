@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun OpenCodeShell(
    ptyService: PtyService? = null,
    onOpenLicenses: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.Chat) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Moss,
            onPrimary = Color.White,
            secondary = Clay,
            onSecondary = Color.White,
            tertiary = Citrus,
            background = Paper,
            surface = Color(0xFFFFFEFA),
            onSurface = Ink,
            onBackground = Ink,
            surfaceVariant = Color(0xFFE8E7DD),
            onSurfaceVariant = Color(0xFF535D58),
        ),
    ) {
        Scaffold(
            containerColor = Paper,
            topBar = {
                TopAppBar(
                    title = {
                        Column {