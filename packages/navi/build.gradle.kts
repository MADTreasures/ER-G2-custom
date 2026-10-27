// App package "Navi" (docs/app-entwicklung/09). The package build in the root build.gradle.kts turns it into
// build/g2app/<id>-<version>.g2app:  ./gradlew :packages:navi:g2app
// Offline navigation on foot and by bike: maps of single countries from the mapsforge server, the watch's GPS,
// routes and turn hints on the glasses. Everything – map reader, drawing, routing, search – is the package's own
// Kotlin; the watch app only lends it its GPS, a folder for big files with downloads, and background work
// (interface 5, 03 §14). See README.md.
