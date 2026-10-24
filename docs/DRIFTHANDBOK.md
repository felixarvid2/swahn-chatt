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
  Titta på trenden: växer disken snabbare än du väntat dig?
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
5. Om bara *en* person saknar aviseringar ligger felet nästan alltid i den telefonen (punkt 4). Om *ingen* får dem
   ligger det på servern (punkt 1 till 3).

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
| Disken fylls | Avsnitt 10 |

Fortfarande fast? Samla `docker compose logs --since 1h backend`, `docker compose ps` och utskriften av
`check-config` (innehåller aldrig hemliga nycklar) innan du frågar någon.
