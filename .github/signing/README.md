# Verlezza Vision: firma permanente Android V1

Scope: ramo LAB isolato. Nessuna chiave privata in chiaro in GitHub, cache, artifact, log, release o commit.

- Cifrato versionato: `Visionflix_Signing_Key_V1.p12.enc.b64`, AES-256-CBC, OpenSSL PBKDF2, 250000 iterazioni, salt casuale.
- SHA-256 dei byte cifrati: `fb15a0054401ca7b8f925977a51e0585965ef5edfb4cca3d00a3d92550526799`.
- SHA-256 certificato pubblico Android: `ae10b6198ccb567386af37ad8b92d9ad40aa5bada469b8528811d447fc9612f6`.
- Alias PKCS12: `visionflix-v1`.
- Secret richiesto nei GitHub Actions Secrets del repository: `VISIONFLIX_SIGNING_V1_PASSWORD`.

Il proprietario deve copiare in quel secret il valore custodito separatamente nel backup `Visionflix_Signing_Recovery_Secret_V1.txt`, ricevuto privatamente. La stessa stringa è password del keystore PKCS12 e della decifrazione. Mai inserirla in repository, commenti GitHub, issue, PR, screenshot, log o messaggi. Il secret è l'unico passaggio di configurazione manuale rimasto e non può essere verificato dal contenuto del repository.

Il workflow `.github/workflows/build-apks.yml` fallisce PRIMA di compilare quando il secret è assente/non valido, la copia cifrata è differente o il certificato non corrisponde. Non esiste fallback ad una chiave temporanea. Mobile e TV sono firmati con lo stesso certificato permanente; il file PKCS12 decifrato vive soltanto sul runner effimero in `RUNNER_TEMP` e non è incluso negli artefatti.

La precedente chiave con SHA-256 `55d9c2dc94f32e6b7685994f2652eec3d8086432e9de94e0645a8bf30f41918e` non è sostituibile in-place. Prima dell'eventuale futura installazione con V1 salvare i dati dell'app, poi pianificare UNA migrazione approvata separatamente. **Non disinstallare nulla adesso.**

Questo README non concede promozione a CANDIDATA/MADRE/produzione né pubblicazione Downloader. Il branch resta LAB fino ad approvazione successiva.
