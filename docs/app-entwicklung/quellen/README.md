# Quellen

Recherche-Notizen vom 29.09.2026, auf Englisch, mit Datei- und Zeilenangaben. Grundlage der Kapitel 01–06.

| Datei | Inhalt | Grundlage |
|---|---|---|
| [A-evenhub-in-faceclaw.md](A-evenhub-in-faceclaw.md) | Wie Faceclaw Even-Hub-Apps ausführt: Paketformat, Brücke, Container, Eingaben, Stand, Übertragbarkeit | Faceclaw, Commit `2052e26` (28.09.2026) |
| [B-evenhub-offiziell.md](B-evenhub-offiziell.md) | Evens offizielle Even-Hub-Doku: SDK, Manifest, Grenzen, Store-Regeln, Bedingungen (mit Zitaten und Links) | hub.evenrealities.com, npm-Pakete `@evenrealities/*` |
| [C-faceclaw-apps-und-fernsteuerung.md](C-faceclaw-apps-und-fernsteuerung.md) | Faceclaws eigenes App-Modell, Display-Liste Revision 35, Wear-OS-Begleiter, Netzwerk-Eingabe, Durchsatz | Faceclaw, Commit `2052e26` |
| [D-browser-auf-der-uhr.md](D-browser-auf-der-uhr.md) | Browser-Engine auf der Uhr: kein WebView auf Wear OS, GeckoView (Größe, Prozesse, Brücke), JS-Engines, Pixel Watch 5, Datenweg Uhr ↔ Handy | Android-/Mozilla-Doku, AOSP, Maven, Berichte (Stand 29.09.2026) |
| [E-m2-messwerte.md](E-m2-messwerte.md) | Vorlage für die Messwerte des Gecko-Tests (M2) auf der echten Uhr und die Entscheidung „GeckoView ja/nein“ – **noch leer** | Test-APK `tools/gecko-probe/` |

Pfadangaben wie `app/apps/evenhub/session.ts:794` beziehen sich auf das Faceclaw-Repo
(https://github.com/jimrandomh/faceclaw) in diesem Commit. Angaben zu Evens Store-Server sind bewusst
weggelassen ([05 §7](../05_EvenHub-Apps.md#7-apps-installieren-und-woher-sie-kommen-dürfen)). Markierungen: **[V]** im Code
oder in der Doku geprüft, **[I]** abgeleitet, **[OFF]** offizielle Quelle, **[COMM]** Community.
