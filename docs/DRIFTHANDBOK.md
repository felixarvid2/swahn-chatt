# FamilyChat: driftshandbok för administratören

Den här handboken är till dig som sköter familjens FamilyChat-server (Raspberry Pi med Docker). Den innehåller bara
kommandon och adresser som finns på riktigt: se [RASPBERRY_PI_SETUP.md](RASPBERRY_PI_SETUP.md) för installationen
och [API.md](API.md) för alla adresser. Användarna har en egen guide: [ANVANDARGUIDE.md](ANVANDARGUIDE.md).

Om inget annat står körs kommandona på Pi:n, i mappen `~/familychat/deployment` (där `docker-compose.yml` ligger).

## Innehåll

1. [Rutiner: dagligen, veckovis, månadsvis](#1-rutiner-dagligen-veckovis-månadsvis)
2. [Hämta en admin-nyckel för API:t](#2-hämta-en-admin-nyckel-för-api-et)
3. [Ny familjemedlem (inbjudan)](#3-ny-familjemedlem-inbjudan)
4. [Tappad eller stulen telefon](#4-tappad-eller-stulen-telefon)
5. [Glömt lösenord](#5-glömt-lösenord)
6. [Ta bort en person](#6-ta-bort-en-person)
7. [Vad innehåller en säkerhetskopia?](#7-vad-innehåller-en-säkerhetskopia)
8. [Återställa från säkerhetskopia, steg för steg](#8-återställa-från-säkerhetskopia-steg-för-steg)
9. [Uppgradera](#9-uppgradera)
10. [Disken är full](#10-disken-är-full)
11. [Certifikat och Tailscale](#11-certifikat-och-tailscale)
12. [Push-aviseringar fungerar inte](#12-push-aviseringar-fungerar-inte)
13. [Granska säkerhetsloggen](#13-granska-säkerhetsloggen)
14. [Felsökningstabell](#14-felsökningstabell)
15. [Larm till din telefon (ntfy)](#15-larm-till-din-telefon-ntfy)
16. [Databasunderhåll och HTTPS-skydd](#16-databasunderhåll-och-https-skydd)
17. [Röst- och videosamtal (TURN)](#17-röst--och-videosamtal-turn)

## 1. Rutiner: dagligen, veckovis, månadsvis

**Dagligen (30 sekunder, eller låt en övervakare göra det).**

```bash
docker compose ps                        # backend ska stå som "healthy"
curl -s http://127.0.0.1:8000/readyz     # {"status":"ready","migration":"..."}
```

`/healthz` säger bara att processen lever. `/readyz` säger att databasen svarar, är migrerad och att disken har
utrymme. Svarar den `503` står orsaken i `code`: `database_unavailable`, `migration_mismatch` (avsnitt 9) eller
`low_disk` (avsnitt 10). Peka gärna en uptime-övervakare på `/readyz` (utifrån: `https://<serveradress>/readyz`).

**Varje vecka (5 minuter).**

- `GET /admin/stats` (avsnitt 2 visar hur du når den): antal användare och enheter, `undelivered_envelopes`
  (meddelanden som väntar på avstängda telefoner), `db_bytes`, `attachments_bytes`, `disk_free_bytes`, `uptime_seconds`.
  Titta på trenden: växer disken snabbare än du väntat dig. `db_maintenance` visar veckans databasunderhåll
  (avsnitt 16): `ok` ska vara `true` och `at` högst ungefär en vecka gammal.
- Loggarna, bara fel de senaste sju dagarna:

  ```bash
  docker compose logs --since 168h backend | grep -i -E 'error|exception|traceback'
  ```

  Loggarna är JSON-rader och innehåller aldrig lösenord, tokens eller meddelandetext. Om en användare rapporterar ett
  fel: be om `request_id` ur felmeddelandet och sök på det: `docker compose logs backend | grep <id>`.
- Säkerhetsloggen (avsnitt 13): kommer det inloggningsförsök du inte känner igen?
- Finns senaste säkerhetskopian, och är den ny?

  ```bash
  ls -lh ~/familychat-backups | tail -n 3        # mappen du angav i familychat-backup.service
  systemctl list-timers 'familychat-*'
  systemctl --failed                              # ska vara tomt
  ```

**Varje månad (automatiskt).** Timern `familychat-verify-backup` kontrollerar att den senaste säkerhetskopian går att
återställa (checksummor, SQLites `integrity_check`, bilagornas sökvägar), utan att röra någon data. Misslyckas den
visas den som failed i `systemctl --failed`. Installation finns i avsnitt 7. Gör också ett riktigt
återställningsprov på en annan maskin någon gång per år (avsnitt 8).

**Några gånger om året.** `docker system df` och `docker image prune -f`. Uppdatera Pi:ns operativsystem
(`sudo apt update && sudo apt full-upgrade`) och starta om: allt ska komma upp av sig självt
(`docker compose ps`, och `tailscale serve status` om du kör Tailscale på värden).

## 2. Hämta en admin-nyckel för API:t

Några uppgifter (statistik, enheter, granska loggen via API) görs mot API:t och kräver en administratörs
åtkomstnyckel. De flesta andra uppgifter går att göra med kommandoraden på Pi:n och behöver ingen nyckel.

Varje inloggning skapar en **ny enhet** på kontot, och nyckeln gäller i 15 minuter. Logga därför ut efteråt så att
enheten försvinner igen.

```bash
API=http://127.0.0.1:8000/api/v1                # på Pi:n; utifrån: https://<serveradress>/api/v1
read -r -p 'Admin-användarnamn: ' ADMIN_USER
read -r -s -p 'Lösenord: ' ADMIN_PW; echo
TOKEN=$(curl -s "$API/auth/login" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json,sys; print(json.dumps({"username": sys.argv[1], "password": sys.argv[2], "device_name": "admin-curl"}))' "$ADMIN_USER" "$ADMIN_PW")" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
unset ADMIN_PW

curl -s -H "Authorization: Bearer $TOKEN" "$API/admin/stats"    # exempel (lägg på  | python3 -m json.tool  för läsbarhet)
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/auth/logout"   # när du är klar: tar bort admin-curl-enheten
```

Är en admin inloggad på sin telefon fungerar appen också: *Inställningar* har inbjudningar och enheter.

## 3. Ny familjemedlem (inbjudan)

```bash
docker compose exec backend python -m app.cli create-invite --note "Mormor" --hours 168
```

Koden (sista raden i utskriften, till exempel `K7M2P-9XQ4W-HT5RB-3NZ8D`) fungerar **en gång** och gäller i
`--hours` timmar (standard 72, högst 720). Lägg till `--admin` bara om personen ska bli administratör.
Administratörer kan också skapa inbjudningar i appen (*Inställningar → Skapa inbjudan*), se och återkalla dem via
`GET /admin/invitations` och `DELETE /admin/invitations/{id}`.

1. Skicka koden på ett sätt du litar på, helst inte i samma meddelande som serveradressen.
2. Skicka appfilen och [ANVANDARGUIDE.md](ANVANDARGUIDE.md). Bjud in personen till Tailscale om ni använder det.
3. Kontrollera efteråt: `docker compose exec backend python -m app.cli list-users` visar kontot och antal enheter.
4. Säg åt familjen att de får ett "ny enhet"-meddelande när personen loggar in. Det är rätt.

## 4. Tappad eller stulen telefon

Målet: telefonen ska sluta få meddelanden och sluta kunna skicka, så snabbt som möjligt.

**Bäst:** personen själv, från en annan enhet: *Inställningar → Enheter →* papperskorgen vid telefonen, och sedan
*Byt lösenord*. Har personen ingen annan enhet gör du det åt hen:

1. Hämta en admin-nyckel (avsnitt 2) och sätt `API` och `TOKEN`.
2. Hitta personens id:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" "$API/admin/users"     # leta efter username, notera "id"
   ```

3. Lista personens aktiva enheter och hitta den borttappade (namnet personen valde vid inloggningen):

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" "$API/users/<user_id>/devices"
   ```

4. Återkalla enheten:

   ```bash
   curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "$API/admin/users/<user_id>/devices/<device_id>" -w '%{http_code}\n'   # 204 = klart
   ```

   Telefonen loggas ut direkt, dess nycklar och köade data raderas och öppna anslutningar stängs (inom en minut).
   `404` betyder att enheten inte är aktiv för den användaren, och `409` att det är din egen inloggade enhet.
5. Personen behöver också **byta lösenord** (eller få en återställningskod, avsnitt 5): då loggas alla andra
   enheter ut. Om du är osäker på om lösenordet är känt för andra: gör det alltid.
6. Personen loggar in på den nya telefonen (ny enhet). Hens kontakter ser "ny enhet" och måste godkänna den.
   Nästa gång personen ska använda en *andra* telefon måste den godkännas på den första
   (*Säkerhetsnummer för mina enheter*), se användarguiden. Har personen tappat **alla** sina telefoner går det inte att
   godkänna något. Då är den snabbaste vägen en återställningskod (avsnitt 5), som skapar en helt ny enhet.
7. Granska loggen (avsnitt 13): `device_revoked` med `by=admin` ska synas.

Allra snabbast när något är akut och du bara vill stänga dörren: `python -m app.cli remove-user <användarnamn>`
(avsnitt 6). Det tar bort hela kontot, så använd det bara om kontot inte ska finnas kvar.

## 5. Glömt lösenord

Det finns inget "glömt lösenord"-mejl: administratören skapar en engångskod.

```bash
docker compose exec backend python -m app.cli reset-password <användarnamn>
```

Koden är sista raden i utskriften, gäller i 24 timmar och fungerar en gång. Ger du en ny kod till samma person
upphör den gamla. Lämna koden muntligt eller på annat sätt du litar på, och be personen skriva in den i appen
tillsammans med ett nytt lösenord (minst 10 tecken). Samma sak kan göras med API:t:
`POST /admin/users/{user_id}/password-reset`.

**Räkna med följderna:** när koden används loggas personen ut **överallt**, alla gamla enheter tas bort (nycklar och
köade meddelanden raderas) och telefonen som använde koden blir en ny enhet. Gamla meddelanden går inte att läsa på
den, och kontakterna ser "ny enhet". Det är samma sak som vid tappad telefon, och det är avsiktligt.

I loggen syns `password_reset_issued` (med `via=cli` eller `admin`) och `password_reset_used`. Koden syns aldrig där.

## 6. Ta bort en person

```bash
docker compose exec backend python -m app.cli list-users
docker compose exec backend python -m app.cli remove-user <användarnamn>
```

Kontot stängs av, alla sessioner och enheter återkallas och personen tas bort ur alla samtal. API-åtkomsten upphör
direkt och öppna anslutningar stängs inom ungefär en minut. Den enda administratören nekas (`--force` krävs).

## 7. Vad innehåller en säkerhetskopia?

En `.tar.gz`-fil med tre delar: `familychat.db` (databasen), alla bilagor och `manifest.json` med antal och
SHA-256-checksummor.

- **Med:** konton, **lösenordshashar**, publika nycklar, vilka som är med i vilka samtal, meddelandemetadata (vem,
  när, hur stort) och **krypterad** text och bilagor.
- **Inte med / inte möjligt:** ingen kan läsa meddelanden ur en säkerhetskopia. Den återskapar inte heller någons
  möjlighet att läsa gamla meddelanden: det beror bara på telefonerna.
- Ändå är filen **känslig** (metadata och lösenordshashar): förvara den privat, filrättigheter 600 sätts av skriptet,
  och kopiera den helst krypterad (rclone `crypt`) om du lägger den utanför Pi:n.
- Telefonernas egna nycklar och historik finns inte i den. En tappad telefon kan alltså inte "återställas" genom servern.

**Schema:** `familychat-backup.timer` tar en kopia varje natt (kl 03:15) och sparar de 14 senaste
(`FAMILYCHAT_BACKUP_KEEP`). Se RASPBERRY_PI_SETUP.md, avsnitt 9.

**Automatisk månadskontroll.** Installera (ändra `User=`, `WorkingDirectory=` och sökvägarna så att de stämmer med
`familychat-backup.service`):

```bash
sudo cp systemd/familychat-verify-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now familychat-verify-backup.timer
sudo systemctl start familychat-verify-backup.service      # prova en gång
journalctl -u familychat-verify-backup -n 20               # "backup OK: ..."
systemctl list-timers familychat-verify-backup.timer
```

Det är samma sak som `./backup.sh --verify-latest <mapp>`: den senaste arkivfilen i mappen kontrolleras inne i
backend-containern. Misslyckas kontrollen (skadad fil, ingen fil, eller en fil som är äldre än
`FAMILYCHAT_BACKUP_MAX_AGE_DAYS`, standard 3 dagar, vilket betyder att nattbackupen har slutat fungera) hamnar
tjänsten i `failed`-läge och syns i `systemctl --failed`. Vill du få ett meddelande: se
`familychat-notify-failure@.service` (valfri OnFailure-krok som skickar ett meddelande till ett ntfy-ämne bara du
prenumererar på; instruktionerna står överst i filen).

Kopiera gärna också en backup till en annan maskin och kontrollera den där:
`docker compose exec backend python -m app.cli verify-backup /data/...` (filen måste ligga på `/data`, se avsnitt 8).

## 8. Återställa från säkerhetskopia, steg för steg

Gäller till exempel ett trasigt SD-kort eller en ny Pi.

1. **Ny Pi:** gör installationen i RASPBERRY_PI_SETUP.md till och med steg 4 (`docker compose up -d --build`), och
   sätt samma `FAMILYCHAT_SECRET_KEY` som förut om du har den kvar (annars loggas alla ut: "token utgången"-loop,
   alla får logga in igen). Samma Tailscale-namn eller domän gör att telefonerna hittar servern utan ändringar.
2. Hämta arkivet, till exempel från fjärrlagringen: `rclone copy familychat-crypt:familychat-backups ~/familychat-backups`.
3. Kontrollera det **innan** du ersätter något. Arkivet ligger på värden, kommandot körs i containern, så strömma
   in filen (så som `backup.sh --verify-latest` gör):

   ```bash
   docker compose exec -T backend sh -c \
     'f=/data/.verify-upload.tar.gz; trap "rm -f $f" EXIT; cat > $f && python -m app.cli verify-backup $f' \
     < ~/familychat-backups/familychat-backup-20260101-031500.tar.gz
   ```

   Utskriften ska börja med `backup OK` och slutstatus vara 0. Annars: ta nästa äldre arkiv.
4. Återställ:

   ```bash
   ./restore.sh ~/familychat-backups/familychat-backup-20260101-031500.tar.gz
   ```

   Skriptet frågar först (`-y` hoppar över frågan), stoppar backend, kontrollerar alla checksummor, flyttar befintlig
   data till `/data/pre-restore-<tid>` i volymen, återställer och startar backend igen. Nya migreringar körs vid start.
5. Kontrollera: `docker compose ps`, `curl -s http://127.0.0.1:8000/readyz`, och `list-users`:

   ```bash
   docker compose exec backend python -m app.cli list-users
   ```

6. Be någon logga in och skicka ett meddelande. Allt som skickades **efter** säkerhetskopian är borta från servern,
   och telefonerna kan behöva en stund för att synka. Meddelanden som redan finns på telefonerna ligger kvar där.
7. När du är nöjd: `docker compose exec backend sh -c 'rm -rf /data/pre-restore-*'`.

Gick något fel: den gamla datan finns kvar i `/data/pre-restore-*`.

## 9. Uppgradera

```bash
cd ~/familychat/deployment
./backup.sh                          # alltid först
git pull
docker compose up -d --build         # bygger om; databasmigreringar körs automatiskt vid start
docker compose ps && curl -s http://127.0.0.1:8000/readyz
docker image prune -f
```

Caddy-, ntfy- och Tailscale-avbilderna är fastlåsta till versioner i `docker-compose.yml` och uppdateras bara när
du ändrar versionen medvetet och kör `docker compose pull && docker compose up -d`.

Går något fel: `git checkout <föregående version>`, `docker compose up -d --build`. Har den nya versionen ändrat
databasen (`/readyz` säger `migration_mismatch`) måste du även `./restore.sh` säkerhetskopian du tog först:
migreringar går bara framåt. Uppdatera appen på telefonerna genom att installera den nya APK:n **över** den gamla.

## 10. Disken är full

Tecken: `/readyz` svarar `503 low_disk` (mindre än `FAMILYCHAT_MIN_FREE_DISK_MB`, standard 200 MB), eller
`disk_free_bytes` i `/admin/stats` är nära noll. Gör något **innan** SQLite inte kan skriva längre.

```bash
df -h /                                        # värdens disk
docker system df                               # vad Docker använder
docker compose exec backend du -sh /data       # datavolymen
```

1. Rensa det som är säkert: `docker image prune -f` och `docker builder prune -f` (gamla avbilder och byggcache).
2. Gamla säkerhetskopior: sänk `FAMILYCHAT_BACKUP_KEEP` i `.env` och kör `./backup.sh --prune ~/familychat-backups`.
   (Flytta inte bort den senaste.)
3. Kvarlämnade filer från återställning: `docker compose exec backend sh -c 'rm -rf /data/pre-restore-*'` (om du har
   kontrollerat att den återställda datan är bra).
4. Loggarna roterar redan (10 MB x 5 per tjänst) och är sällan problemet.
5. Bilagor är oftast det som växer. `GET /admin/users` visar `attachment_bytes` per person. Prata med den som laddar
   upp mest, eller sänk `FAMILYCHAT_USER_QUOTA_MB` / `FAMILYCHAT_MAX_ATTACHMENT_MB` i `.env` (gäller nya uppladdningar;
   kör `docker compose up -d` för att läsa in det och `check-config` för att kontrollera).
6. Räcker det inte: större disk eller SSD. Stoppa backend, `./backup.sh` först (om det finns plats), flytta Docker-datan eller
   återställ på ny disk enligt avsnitt 8.

Meddelanden som levererats raderas från servern efter `FAMILYCHAT_ENVELOPE_RETENTION_DAYS` (30) dagar av en
timjobb. Meddelanden som väntar på telefoner som aldrig kommer online (se `undelivered_envelopes`) tar plats tills
dess. En enhet som är död bör återkallas (avsnitt 4).

## 11. Certifikat och Tailscale

Symtom i appen: "Servern har inget giltigt certifikat" eller "Servern svarar inte".

**Börja alltid så här:** funkar servern lokalt? `curl -s http://127.0.0.1:8000/readyz` på Pi:n. Gör den det ligger
felet i vägen dit (Tailscale/Caddy/nät), inte i FamilyChat. Gör den det inte: avsnitt 14.

**Tailscale på värden:**

```bash
tailscale status                      # är Pi:n ansluten till tailnätet?
sudo tailscale up                     # om inte
tailscale serve status                # ska visa https://<namn>.<tailnet>.ts.net -> http://127.0.0.1:8000
sudo tailscale serve --bg --https=443 http://127.0.0.1:8000   # om raden saknas
```

Kontrollera i Tailscales administratörskonsol att **MagicDNS** och **HTTPS Certificates** är på (DNS-sidan), att Pi:ns
nyckel inte gått ut (*Disable key expiry* för Pi:n) och att telefonens Tailscale är ansluten. Använd aldrig
`tailscale funnel` här: det lägger inloggningen öppet på internet.

**Tailscale som container (profilen `tailscale`):**

```bash
docker compose logs tailscale           # "Startup complete"? en inloggningslänk?
docker compose --profile tailscale up -d
```

Behåll volymen `tailscale-state`: tas den bort registreras noden på nytt under ett nytt namn och adressen ändras
(då måste alla ändra serveradressen i appen). `TS_AUTHKEY` i `.env` behövs bara första gången.

**Caddy med egen domän:**

```bash
docker compose logs caddy               # certifikatfel? "rate limited"?
```

Port 80 och 443 måste nå Pi:n (router och brandvägg), och domänen peka på rätt adress. Behåll volymen `caddy-data`
(certifikaten): Let's Encrypt begränsar hur ofta nya utfärdas.

**Klockan.** Fel tid på Pi:n eller telefonen ger certifikatfel och "token utgången"-loopar: `timedatectl` på Pi:n
(ska säga `System clock synchronized: yes`).

## 12. Push-aviseringar fungerar inte

Utan push kommer meddelandena ändå fram, men först när appen är öppen eller var 15:e minut. Gå igenom i ordning:

1. **Är ntfy uppe?** `docker compose ps` (profilen `ntfy`) och `docker compose logs ntfy`. Testa adressen i en webbläsare.
2. **Pekar backend på rätt server?** `check-config` varnar för push-inställningar:

   ```bash
   docker compose exec backend python -m app.cli check-config
   ```

   Kontrollera `FAMILYCHAT_PUSH_ALLOWED_HOSTS` (ntfy-värdens namn måste finnas med i produktion) och att
   `FAMILYCHAT_PUSH_ALLOW_HTTP=false` med en `https://`-adress.
3. **Syns försök i loggen?** `docker compose logs --since 1h backend | grep -i push`.
4. **Telefonen:** ntfy-appens *Standardserver* ska vara er `NTFY_BASE_URL`; i FamilyChat ska *Inställningar →
   Pushnotiser* visa "Aktiv via ntfy"; och **batterioptimering** ska vara avstängd ("Obegränsad") för både ntfy och
   FamilyChat. Det är den vanligaste orsaken, särskilt på Samsung, Xiaomi, Huawei och OnePlus (https://dontkillmyapp.com).
5. **Hur gick det senaste försöket?** Som administratör ser du per enhet hur den senaste pushen gick:
   `GET /admin/users` (avsnitt 2) har under varje konto en lista `devices` med `push_registered`, `last_push_at`,
   `last_push_status` (HTTP-svaret från ntfy; `0` betyder inget svar alls, alltså nätverksfel eller timeout) och
   `push_failures` (antal pushar i rad som misslyckats helt). Servern försöker själv igen efter 5 sekunder, 30 sekunder
   och 2 minuter vid tillfälliga fel (nätverksfel, 429, 5xx), så ett enstaka fel syns inte som misslyckande. Hög
   `push_failures` med status `0` pekar på ntfy eller nätet, `403`/`401` på ntfy:s åtkomstregler, `404`/`410` betyder att
   ntfy har glömt prenumerationen (servern rensar då adressen).
6. **Testa en enda telefon.** Personen kan trigga `POST /devices/me/push/test` (en testpush, högst 3 per 10 minuter);
   resultatet står i `last_push_at` och `last_push_status` på `GET /devices/me` en stund senare. `409 push_not_configured`
   betyder att telefonen inte har registrerat någon push-adress eller att `FAMILYCHAT_PUSH_ALLOWED_HOSTS` är tom.
7. Om bara *en* person saknar aviseringar ligger felet nästan alltid i den telefonen (punkt 4). Om *ingen* får dem
   ligger det på servern (punkt 1 till 3 och 5).

## 13. Granska säkerhetsloggen

```bash
docker compose exec backend python -m app.cli audit --limit 200      # äldst först, nyast sist
docker compose exec backend python -m app.cli audit --limit 1000 | grep -E 'login_failure|refresh_token_reuse|device_revoked|password_reset|user_removed'
```

Via API: `GET /admin/audit?limit=200` (nyast först; `before_id=<minsta id>` ger nästa sida). Loggen sparas i 180 dagar
(`FAMILYCHAT_AUDIT_RETENTION_DAYS`).

Vad du letar efter:

| Händelse | Normalt | Titta närmare om |
|---|---|---|
| `login_failure` | Enstaka, någon som skrev fel | Många i rad, från en okänd `ip=`, mot ett konto som inte ska användas |
| `login_success` | Telefon eller en ny enhet du känner till | Inloggning du inte kan förklara (ring personen) |
| `refresh_token_reuse` | Sällan | Upprepad: en inloggning har kopierats. Återkalla enheten, byt lösenord |
| `invitation_created` / `invitation_redeemed` | Du skapade den, rätt person löste in den | Inlösen du inte väntade dig |
| `admin_granted` | Bara när du bjudit in en administratör | Alltid något att utreda om du inte gjorde det |
| `device_revoked` (`by=self/admin/logout`) | Ny telefon, utloggning | `by=admin` som du inte gjorde |
| `password_reset_issued` / `_used` | Du hjälpte någon | En reset du inte gjorde |
| `user_removed` | Du gjorde det | Annars |

Loggen innehåller aldrig lösenord, koder eller nycklar. Den ligger i serverns databas: den som kontrollerar servern
kan ändra den, så den är en anteckning till familjen och inte bevis.

## 14. Felsökningstabell

| Symptom | Titta på |
|---|---|
| Containern startar om hela tiden | `docker compose logs backend`: oftast saknad eller för kort `FAMILYCHAT_SECRET_KEY` (minst 32 byte). `check-config` visar felet |
| "Token utgången" i en slinga | Telefonens klocka går fel, eller `FAMILYCHAT_SECRET_KEY` har ändrats (alla loggar in igen) |
| `429 rate_limited` | För många försök. Vänta `Retry-After` sekunder. Räknarna ligger i minnet: en omstart av backend nollställer dem |
| `409 device_limit` vid inloggning | Kontot har redan så många enheter som tillåts (standard 10). Ta bort en gammal enhet (avsnitt 4) |
| En återkallad enhet verkar ändå ansluten | Anslutningarna stängs inom `FAMILYCHAT_WS_REVALIDATE_SECONDS` (60 s) |
| `/readyz` ger `migration_mismatch` | Databasen och programversionen stämmer inte: avsnitt 9 |
| Inga aviseringar | Avsnitt 12 |
| Inga larm till telefonen | Avsnitt 15 |
| `db_maintenance` i `/admin/stats` är `null` eller har `ok: false` | Avsnitt 16 |
| Disken fylls | Avsnitt 10 |

Fortfarande fast? Samla `docker compose logs --since 1h backend`, `docker compose ps` och utskriften av
`check-config` (innehåller aldrig hemliga nycklar) innan du frågar någon.

## 15. Larm till din telefon (ntfy)

Servern kan skicka ett kort larm till ett ntfy-ämne som bara du prenumererar på. Det är valfritt och av som standard.
Skapa ett ämne med ett långt slumpmässigt namn (alla som känner till adressen kan läsa larmen) och lägg i `.env`:

```bash
FAMILYCHAT_ADMIN_NTFY_URL=https://ntfy.example.org/familychat-admin-x7k2q9w4m1    # hela ämnesadressen, https
FAMILYCHAT_ADMIN_NTFY_TOKEN=                                                      # bara om ntfy kräver inloggning
```

Starta om med `docker compose up -d`. Servern vägrar starta om adressen inte är `https://` i produktion. Larmen är fasta
korta texter utan användarnamn, adresser, meddelanden eller hemligheter:

| Larm | När | Som tätast |
|---|---|---|
| Low disk space | Disken har mindre än `FAMILYCHAT_MIN_FREE_DISK_MB` ledigt (`/readyz` eller timjobbet) | var 6:e timme |
| Server maintenance failed | Timjobbet eller veckans databasunderhåll misslyckades (se loggen) | var 6:e timme |
| A login lockout was triggered | Ett användarnamn har blivit utelåst efter för många felaktiga lösenord (säger inte vilket) | var 30:e minut |
| A refresh token was reused | En gammal inloggningsnyckel kom tillbaka: sessionen återkallades. Kontrollera säkerhetsloggen (avsnitt 13) | var 10:e minut |
| A new administrator was granted | Någon blev administratör (inbjudan eller befordran) | varje minut |

Högst 20 larm per timme totalt. Larmet skickas i bakgrunden: är ntfy nere märks det bara som en varning i loggen
(`docker compose logs backend | grep -i alert`), aldrig som ett fel för användarna. Servern ser **inte** om
nattbackupen misslyckas (den körs utanför servern): använd `familychat-notify-failure@.service` (avsnitt 7).
Larm för en ny administratör som skapas direkt med kommandoraden (`create-invite --admin`) kommer när inbjudan
löses in.

## 16. Databasunderhåll och HTTPS-skydd

**Veckojobbet.** Backend kör en gång i veckan (`FAMILYCHAT_DB_MAINTENANCE_DAYS`, standard 7, `0` stänger av) ett
underhåll av databasen, i bakgrunden och inte i någon begäran. SQLite: `PRAGMA optimize`, `ANALYZE`, tömning av
WAL-filen och `VACUUM` bara om mer än 20 % av databasfilen är tomma sidor och disken har gott om plats (1,25 gånger
databasens storlek). `VACUUM` skriver om hela filen och stoppar skrivningar medan den pågår (sekunder för en
familjedatabas), så den väntar tills ingen använt servern på en minut och provas om varje timme tills det blir tyst.
PostgreSQL: bara `ANALYZE`, resten sköter autovacuum. Resultatet loggas (`docker compose logs backend | grep dbmaint`)
och står i `db_maintenance` i `GET /admin/stats`: `vacuum` är `done`, `not_needed`, `disk_space` (för lite ledig disk,
frigör plats enligt avsnitt 10), `busy` (väntar på ett lugnt ögonblick) eller `n/a`. Ett misslyckat underhåll provas om
vid nästa timkörning och ger larmet "Server maintenance failed" (avsnitt 15).

**HTTPS-skydd (HSTS).** Sätt `FAMILYCHAT_HSTS=true` i `.env` först när *alla* når servern via HTTPS (Caddy eller
`tailscale serve`): webbläsare vägrar då vanlig http mot adressen i ett år. Är du osäker, låt den vara av. Övriga
säkerhetshuvuden (`nosniff`, `X-Frame-Options`, CSP m.fl.) skickas alltid och kräver ingen inställning. Sidan
`/api/v1/docs` (bara om du slagit på `FAMILYCHAT_EXPOSE_DOCS`) hämtar sin kod från `cdn.jsdelivr.net` och har därför en
något lösare policy.

## 17. Röst- och videosamtal (TURN)

Samtal går direkt mellan telefonerna (WebRTC, krypterat från ände till ände). Servern skickar bara krypterade
signalmeddelanden (typ `call`, sparas högst `FAMILYCHAT_CALL_RETENTION_SECONDS`, standard 600 s, och syns aldrig i
historiken) och delar ut tillfälliga reläuppgifter via `GET /api/v1/calls/turn`. Hela installationen finns i
[RASPBERRY_PI_SETUP.md](RASPBERRY_PI_SETUP.md), avsnitt 12.

**Behövs ett relä?** Är alla telefoner på samma Tailscale-nät går samtalen oftast direkt: lämna `FAMILYCHAT_TURN_URLS`
tomt. Kör reläet (coturn) om samtal saknar ljud/bild på mobilnät eller om någon inte är på tailnätet.

```bash
openssl rand -base64 48        # lägg värdet i FAMILYCHAT_TURN_SECRET i .env (minst 32 byte, loggas aldrig)
# .env: FAMILYCHAT_TURN_URLS=turn:pi.ditt-nat.ts.net:3478?transport=udp,turn:pi.ditt-nat.ts.net:3478?transport=tcp
docker compose --profile turn up -d && docker compose up -d      # relä + backend läser de nya värdena
docker compose ps                                                # turn ska vara "healthy"
docker compose logs turn | tail
```

- **Portar:** 3478 UDP+TCP och 49160-49200 UDP (och 5349 för `turns:` om du lagt certifikat i `deployment/turn-certs/`).
  Med bara Tailscale: öppna inget i routern, tillåt portarna på `tailscale0` i brandväggen. Publikt: vidarebefordra exakt
  dessa portar och sätt `TURN_EXTERNAL_IP`. Reläet använder värdnätverk och lyssnar på alla Pi:ns gränssnitt, så brandväggen avgör vem som når det.
- **Tailnätsadresser:** reläet nekar som standard att skicka till `100.64.0.0/10` (Tailscale) och till alla privata nät, så
  en läckt reläuppgift inte kan användas för att nå andra enheter på ditt tailnät eller hemnätet. Sätt
  `TURN_ALLOW_TAILNET_PEERS=true` bara om samtal mellan två telefoner på tailnätet verkligen måste gå via reläet, och
  begränsa då Pi:ns åtkomst med Tailscale-ACL:er.
- **Integritet:** reläet ser telefonernas IP-adresser och trafikmängd, aldrig innehållet. Vid direkta samtal ser
  parterna varandras IP-adresser, om inte appen använder "bara relä". Push för inkommande samtal är en vanlig
  innehållsfri push med hög prioritet; ntfy-servern ser bara "brådskande".
- **Felsökning:** `docker compose logs turn` (felrad om hemligheten är för kort eller saknas), `docker compose logs backend`
  (backend vägrar starta om `FAMILYCHAT_TURN_URLS` är satt utan hemlighet på minst 32 byte), och
  `python -m app.cli check-config` visar `turn: N url(s)` utan att skriva ut hemligheten. Byt hemlighet genom att ändra
  `FAMILYCHAT_TURN_SECRET` och köra `docker compose --profile turn up -d --force-recreate` samt `docker compose up -d`;
  pågående samtal klarar sig tills deras uppgifter går ut (en timme).
- **Uppgradering:** bilden är fastlåst (`coturn/coturn:4.18.0`); byt version medvetet i `docker-compose.yml`, läs
  releaseanteckningarna och kör `docker compose --profile turn pull`.
