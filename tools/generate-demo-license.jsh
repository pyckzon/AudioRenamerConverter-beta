import com.casl.mp3converter.core.license.*;
var payload = new LicenseCodec.LicensePayload("PRO", "demo@casl.dev", "MP3C-DEMO-01", java.time.Instant.now().toString());
var bytes = LicenseCodec.encode(payload);
java.nio.file.Files.write(java.nio.file.Path.of("C:/Users/Pyckzon/IdeaProjects/mp3Converter/app-v2-freemium/build/jpackage/mp3ConverterFree/mp3converter.lic"), bytes);
System.out.println("licencia escrita: " + bytes.length + " bytes");
/exit
