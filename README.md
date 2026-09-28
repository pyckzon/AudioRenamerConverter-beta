# Audio Renamer + MP3 Converter

Aplicación de consola Java 17 para Windows que:

1. Pide una carpeta origen.
2. Busca archivos de audio de forma recursiva, incluyendo subcarpetas.
3. Lee `artist` y `title` con FFprobe.
4. Elimina un sufijo final ` - Topic` del artista, sin importar mayúsculas/minúsculas.
5. Genera el nombre estándar `Artista - Título.mp3`.
6. Para formatos con pérdida usa 192 kbps si la fuente es de 192 kbps o menor/desconocida.
7. Para formatos con pérdida que superan 192 kbps usa 320 kbps.
8. Para formatos lossless (FLAC, WAV, ALAC, APE, AIFF) usa 320 kbps.
9. Convierte a MP3 mediante FFmpeg.
10. Copia los metadatos del archivo original mediante `-map_metadata 0`.
11. Conserva la estructura relativa de las subcarpetas dentro de la carpeta destino.
12. Si existen nombres duplicados dentro de una misma carpeta, agrega `(2)`, `(3)`, etc.
13. Nunca modifica ni elimina los archivos originales.

## Requisitos

- Windows 10/11
- Java 17+
- Maven 3.9+
- FFmpeg + FFprobe en PATH

Comprobar:

```powershell
java -version
mvn -version
ffmpeg -version
ffprobe -version
```

## Compilar

Desde la raíz del proyecto:

```powershell
mvn clean package
```

El JAR ejecutable queda en:

```text
target\audio-renamer-converter-1.0.0.jar
```

Ejecutar:

```powershell
java -jar target\audio-renamer-converter-1.0.0.jar
```

## Crear EXE

Con JDK 17+ y `jpackage`:

```powershell
jpackage `
  --type exe `
  --name AudioRenamerConverter `
  --input target `
  --main-jar audio-renamer-converter-1.0.0.jar `
  --main-class com.casl.audiotools.Main `
  --win-console `
  --dest dist
```

Esto genera un instalador `.exe`.

## Política de calidad

El programa aplica esta regla:

- MP3/AAC/OGG/OPUS/WMA/etc. <= 192 kbps -> MP3 192 kbps
- MP3/AAC/OGG/OPUS/WMA/etc. > 192 kbps -> MP3 320 kbps
- FLAC/WAV/ALAC/APE/AIFF -> MP3 320 kbps

Los formatos lossless se envían directamente a 320 kbps porque su bitrate reportado no es una medida comparable con el bitrate de un formato con pérdida.

## Estructura de carpetas

La estructura relativa de la carpeta origen se conserva en destino.

Ejemplo:

```text
ORIGEN
├── Rock
│   ├── archivo1.flac
│   └── archivo2.m4a
└── Pop
    └── archivo3.mp3
```

produce:

```text
DESTINO
├── Rock
│   ├── Artista - Cancion1.mp3
│   └── Artista - Cancion2.mp3
└── Pop
    └── Artista - Cancion3.mp3
```

Los archivos originales permanecen intactos.

## Metadatos

FFmpeg conserva los metadatos compatibles mediante:

```text
-map_metadata 0
```

El nombre del archivo se construye específicamente desde:

```text
artist + " - " + title
```

El campo de artista se limpia con:

```text
(?i)\s*-\s*Topic\s*$
```

Por ejemplo:

```text
Artist - Topic
```

se convierte en:

```text
Artist
```

y el archivo final:

```text
Artist - Cancion.mp3
```
