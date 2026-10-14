# iOSDroid · Reloj Glass

Widget para la pantalla de inicio de Android con **hora, fecha y clima**, con estilo iOS y efecto vidrio (Liquid Glass).

- **Hora grande** (`TextClock`: se actualiza sola cada minuto, sin gastar batería).
- **Fecha** en el idioma del teléfono: *martes, 13 de octubre*.
- **Clima actual** con ícono, estado y máx./mín. del día ([Open-Meteo](https://open-meteo.com), gratis y sin API key).
- **Próximos días**: *Hoy, Mañana, Jue…* con barras de temperatura de colores como el widget de Clima de iOS
  (el punto blanco en "Hoy" es la temperatura actual).
- **Cinco tamaños**: 2×2, 3×2, 3×3 (por defecto), 5×2 y 5×3; se adapta al redimensionarlo.
- **Tres vidrios**: Transparente, Esmerilado y Ahumado. Texto blanco, negro o automático según el fondo de pantalla.
- **Fuente de la hora** (como la pantalla de bloqueo de iOS): Fina, Ligera, Normal, Gruesa, Negra, Condensada, Serif,
  Mono y Manuscrita. Los pesos usan la fuente del sistema, así que en ColorOS se ven con **OPPO Sans**.
- **Fuentes propias**: con «Cargar fuentes» se eligen uno o varios archivos .ttf / .otf (por ejemplo SF Pro), o desde
  el administrador de archivos, WhatsApp o Telegram: *Abrir con* / *Compartir* → iOSDroid. Cada archivo aparece como
  un chip con el nombre que trae la fuente (p. ej. "Bold · SF Pro Display"). Se usan en el modo reloj, la pantalla
  bloqueada y el widget; en el widget la hora se dibuja como imagen y se actualiza cada minuto con la pantalla
  encendida (lo hace el servicio en segundo plano). Con fuentes variables se puede elegir el peso.
  La app no incluye fuentes de terceros: las de Apple (SF Pro) no se pueden redistribuir.
- **Fecha con la misma fuente** (*Usar también en la fecha*, activado por defecto): la fecha toma la familia de la hora
  (Serif, Condensada, Mono, Manuscrita o tu fuente propia) en peso medio.
- Toques: hora → alarmas, fecha → calendario, clima → ajustes.

## Modo reloj y protector de pantalla

- **Modo reloj**: hora gigante, fecha, clima y pronóstico a pantalla completa. La pantalla **no se apaga** mientras está
  abierto y se sigue viendo con el teléfono bloqueado. En horizontal se ve como StandBy de iOS. Fondo negro (ideal OLED)
  o tarjeta de vidrio sobre tu fondo de pantalla, y opción de brillo bajo para la noche. Se abre desde la app o
  manteniendo presionado el ícono → *Modo reloj*. Para salir: tocar una vez y *Salir*.
- **Reloj en la pantalla bloqueada** (*Mostrar al bloquear el teléfono*): cada vez que se bloquea el teléfono
  (botón o tiempo de espera), el reloj aparece encima de la pantalla de bloqueo. Modos: *Siempre* (la pantalla queda
  encendida; con el sensor de proximidad se apaga en el bolsillo), *Al cargar* y *Normal* (aparece al encender la
  pantalla). Se cierra solo al desbloquear con huella/cara, o con *Desbloquear*. Funciona con un servicio en primer
  plano que escucha el apagado de pantalla y una notificación de pantalla completa (como las alarmas); se reactiva
  solo al reiniciar el teléfono.
- **Protector de pantalla del sistema** (*Reloj Glass*): se activa solo mientras el teléfono carga. En ColorOS el menú
  está oculto; el botón de la app abre esa pantalla de Ajustes directamente.
- Para cuidar la pantalla, el contenido se mueve un poco cada minuto (sin animación si está activado "Reducir movimiento").

## Instalar

1. Descargar `app-release.apk` (artefacto **iOSDroid-apk** de GitHub Actions, o compilarlo).
2. Abrirlo en el teléfono y permitir "instalar apps desconocidas" para el navegador o el gestor de archivos.
3. Abrir **iOSDroid**, aceptar la ubicación (o buscar la ciudad a mano) y tocar **Añadir a la pantalla de inicio**.
   También se puede: mantener presionado el inicio → Widgets → iOSDroid → Reloj Glass.

Si el clima deja de actualizarse, en Ajustes → Batería permitir que iOSDroid funcione en segundo plano.

## Compilar

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # tests + capturas del widget en app/build/snapshots/
python3 tools/gen_widget_layouts.py app/src/main/res/layout   # regenera los layouts del widget
```

Requiere JDK 17+ y el Android SDK (compileSdk 36).

## Estilo

Sigue la guía de diseño de iOS (Human Interface Guidelines): espaciado en múltiplos de 4 dp, colores del sistema
(azul `#007AFF`, verde `#34C759`, etiquetas primaria/secundaria/terciaria), jerarquía tipográfica (Large Title,
Headline, Body, Footnote), grupos en tarjetas, filas con ›, interruptores y controles segmentados, respuesta al tacto
(escala + opacidad ~100 ms y vibración leve de selección) y descripciones para lectores de pantalla.

## Notas

- Un widget de Android no puede desenfocar ni refractar el fondo de pantalla que tiene detrás. El vidrio se dibuja en un
  bitmap a la medida del widget (`GlassRenderer`): cuerpo translúcido, brillo superior, resplandor en el canto, canto con
  degradé y destellos en las esquinas.
- `app/signing/iosdroid.jks` es una llave de uso personal incluida a propósito para que cualquier build (local o CI) se
  pueda instalar encima de la anterior sin desinstalar. No usarla para publicar en Play Store.
- Datos del clima: Open-Meteo.com (CC BY 4.0).
