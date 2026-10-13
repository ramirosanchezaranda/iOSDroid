# iOSDroid · Reloj Glass

Widget para la pantalla de inicio de Android con **hora, fecha y clima**, con estilo iOS y efecto vidrio (Liquid Glass).

- **Hora grande** (`TextClock`: se actualiza sola cada minuto, sin gastar batería).
- **Fecha** en el idioma del teléfono: *martes, 13 de octubre*.
- **Clima actual** con ícono, estado y máx./mín. del día ([Open-Meteo](https://open-meteo.com), gratis y sin API key).
- **Tres tamaños**: 2×2, 3×2 (por defecto, entra en el hueco al lado de un widget 2×2) y 3×3; se adapta al redimensionarlo.
- **Tres vidrios**: Transparente, Esmerilado y Ahumado. Texto blanco, negro o automático según el fondo de pantalla.
- **Fuente del sistema**: usa la familia del teléfono con pesos fino/medio, así que en ColorOS se ve con **OPPO Sans**.
- Toques: hora → alarmas, fecha → calendario, clima → ajustes.

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
```

Requiere JDK 17+ y el Android SDK (compileSdk 36).

## Notas

- Un widget de Android no puede desenfocar ni refractar el fondo de pantalla que tiene detrás. El vidrio se dibuja en un
  bitmap a la medida del widget (`GlassRenderer`): cuerpo translúcido, brillo superior, resplandor en el canto, canto con
  degradé y destellos en las esquinas.
- `app/signing/iosdroid.jks` es una llave de uso personal incluida a propósito para que cualquier build (local o CI) se
  pueda instalar encima de la anterior sin desinstalar. No usarla para publicar en Play Store.
- Datos del clima: Open-Meteo.com (CC BY 4.0).
