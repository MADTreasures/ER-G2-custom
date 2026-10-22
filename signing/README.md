# Signaturschlüssel für Uhr-App und G2 Handy

`g2-debug.keystore` signiert die Debug-Builds der Uhr-App (`app/`) und von „G2 Handy“ (`phone/`), auf jedem
Rechner und in GitHub Actions gleich. Die Wear-OS-Datenschicht stellt Nachrichten nur zwischen Apps mit
derselben App-ID und **demselben Schlüssel** zu ([05 §6](../docs/app-entwicklung/05_EvenHub-Apps.md#6-engine-auf-dem-handy-g2-handy));
deshalb liegt der Schlüssel im Repo.

- Es ist ein Debug-Schlüssel (Passwort `android`, Alias `androiddebugkey`), kein Geheimnis. Er schützt nichts,
  er sorgt nur dafür, dass beide Apps gleich signiert sind und Updates über die alte Version passen.
- Zertifikat (SHA-256): `91:8E:AE:A7:EB:62:AB:45:9C:5E:94:C6:60:53:F8:0F:BF:80:04:5E:0D:B5:F0:2E:00:02:B1:E3:94:12:0C:84`
- Eine Uhr-App, die vor 0.10.0 mit einem anderen Schlüssel installiert wurde (jeder CI-Lauf und jeder Rechner
  hatte einen eigenen), lässt sich nicht darüber installieren: einmal deinstallieren, dann neu installieren.
  Dabei gehen ihre Einstellungen und die installierten App-Pakete verloren.
- Den Schlüssel nicht austauschen: Sonst passen Updates wieder nicht, und Fluency auf dem Handy, das sich das
  Zertifikat von G2 Handy merkt, nimmt G2 Handy erst nach „Vergessen“ in seinen Optionen wieder an.
