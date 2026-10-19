#!/usr/bin/env python3
"""Write res/values{,-es,-ca}/strings.xml from one table: English (default),
Spanish and Valencian side by side, so the three stay in step.

    python3 scripts/gen_strings.py

Add or change a string here, never in the XML files.
"""

# key: (en, es, ca-valencia)
S = {
"app_name": ("Skypost","Skypost","Skypost"),
# Common
"add": ("Add","Añadir","Afegir"),
"activate": ("Turn on","Activar","Activar"),
"back": ("Back","Volver","Tornar"),
"cancel": ("Cancel","Cancelar","Cancel·lar"),
"close": ("Close","Cerrar","Tancar"),
"copy": ("Copy","Copiar","Copiar"),
"copied": ("Copied","Copiado","Copiat"),
"create": ("Create","Crear","Crear"),
"go_ahead": ("Continue","Continuar","Continuar"),
"more_options": ("More options","Más opciones","Més opcions"),
"remove": ("Remove","Quitar","Llevar"),
"restore": ("Restore","Restaurar","Restaurar"),
"save": ("Save","Guardar","Guardar"),
"send": ("Send","Enviar","Enviar"),
"share": ("Share","Compartir","Compartir"),
"trust": ("Trust","Confiar","Confiar"),
"settings": ("Settings","Ajustes","Configuració"),
"pending_phase2": ("Coming in phase 2","Pendiente (fase 2)","Pendent (fase 2)"),
"code_not_recognised": ("Not a Skypost or MeshCore code","No es un código de Skypost ni de MeshCore","No és un codi de Skypost ni de MeshCore"),
"paste_code": ("Paste code","Pegar código","Apegar codi"),
"qr_description": ("QR code","Código QR","Codi QR"),
# Scan
"scan_title": ("Scan code","Escanear código","Escanejar codi"),
"scan_hint": ("Point the camera at the QR code","Apunta la cámara al código QR","Apunta la càmera al codi QR"),
"scan_permission": ("To scan QR codes the app needs the camera. Everything stays on this phone.","Para escanear códigos QR la app necesita la cámara. Todo se queda en este teléfono.","Per a escanejar codis QR l'aplicació necessita la càmera. Tot es queda en este telèfon."),
"scan_allow_camera": ("Allow camera","Permitir la cámara","Permetre la càmera"),
# Onboarding
"onboarding_title": ("Welcome to Skypost","Te damos la bienvenida a Skypost","Et donem la benvinguda a Skypost"),
"onboarding_body": ("Messages over radio, without coverage. First create your identity: a key only this phone has, and a name so people recognise you.","Mensajes por radio sin cobertura. Primero crea tu identidad: es una clave que solo tiene este teléfono y un nombre para que te reconozcan.","Missatges per ràdio sense cobertura. Primer crea la teua identitat: una clau que només té este telèfon i un nom perquè et reconeguen."),
"onboarding_create": ("Create identity","Crear identidad","Crear identitat"),
"onboarding_restore": ("Restore a backup","Restaurar copia de seguridad","Restaurar una còpia de seguretat"),
"alias": ("Name","Nombre","Nom"),
"alias_help": ("Up to 16 letters or numbers, no spaces. People will write to you as @name.","Hasta 16 letras o números, sin espacios. Así te escribirán: @nombre.","Fins a 16 lletres o números, sense espais. Així t'escriuran: @nom."),
"callsign_optional": ("Callsign (optional)","Indicativo (opcional)","Indicatiu (opcional)"),
"callsign_help": ("Only if you hold an amateur radio licence, e.g. EA5XXX.","Solo si tienes licencia de radioaficionado, p. ej. EA5XXX.","Només si tens llicència de radioaficionat, p. ex. EA5XXX."),
"no_callsign": ("No callsign","Sin indicativo","Sense indicatiu"),
# Kinds
"kind_direct": ("Direct","Directo","Directe"),
"kind_group": ("Group","Grupo","Grup"),
"kind_signal": ("Signal","Signal","Signal"),
"kind_whatsapp": ("WhatsApp","WhatsApp","WhatsApp"),
"kind_meshcore": ("MeshCore","MeshCore","MeshCore"),
"kind_direct_body": ("Someone else with Skypost, over radio","Otra persona con Skypost, por radio","Una altra persona amb Skypost, per ràdio"),
"kind_group_body": ("Several people with Skypost and a group key","Varias personas con Skypost y una clave de grupo","Diverses persones amb Skypost i una clau de grup"),
"kind_signal_body": ("Through your Pharos, to a Signal number or username","Vía tu Pharos, a un número o usuario de Signal","A través del teu Pharos, a un número o usuari de Signal"),
"kind_whatsapp_body": ("Through your Pharos, to a WhatsApp number","Vía tu Pharos, a un número de WhatsApp","A través del teu Pharos, a un número de WhatsApp"),
"kind_meshcore_body": ("Through your Pharos, to a MeshCore contact","Vía tu Pharos, a un contacto de MeshCore","A través del teu Pharos, a un contacte de MeshCore"),
"needs_pharos": ("You need a Pharos. Tap to add one.","Necesitas un Pharos. Toca para añadir uno.","Necessites un Pharos. Toca per a afegir-ne un."),
# Chat list
"new_chat": ("New chat","Nuevo chat","Xat nou"),
"my_code": ("My QR code","Mi código QR","El meu codi QR"),
"filter_all": ("All","Todos","Tots"),
"chats_empty": ("No chats yet. Add someone by scanning their QR code.","Aún no tienes chats. Añade a alguien escaneando su código QR.","Encara no tens xats. Afig algú escanejant el seu codi QR."),
"chats_empty_filter": ("No chats of this kind.","No hay chats de este tipo.","No hi ha xats d'este tipus."),
"chat_empty_preview": ("No messages","Sin mensajes","Sense missatges"),
"sos": ("SOS","SOS","SOS"),
"sos_confirm_title": ("Send SOS?","¿Enviar SOS?","Enviar SOS?"),
"sos_confirm_body": ("An emergency alert with your position will go to: %1$s. It is not encrypted, so anyone who hears it can help.","Se enviará un aviso de emergencia con tu posición a: %1$s. Va sin cifrar para que cualquiera que lo oiga pueda ayudar.","S'enviarà un avís d'emergència amb la teua posició a: %1$s. Va sense xifrar perquè qualsevol que el senta puga ajudar."),
"sos_no_targets": ("There is nobody to alert yet. Choose your emergency contacts in Settings.","Aún no hay a quién avisar. Elige tus contactos de emergencia en Ajustes.","Encara no hi ha a qui avisar. Tria els teus contactes d'emergència en Configuració."),
"sos_send": ("Send SOS","Enviar SOS","Enviar SOS"),
"sos_sent": ("SOS sent","SOS enviado","SOS enviat"),
"sos_nowhere": ("There is nowhere to send the SOS","No hay ningún sitio al que enviar el SOS","No hi ha cap lloc on enviar el SOS"),
# Chat
"message_hint": ("Message","Mensaje","Missatge"),
"quick_messages": ("Quick messages","Mensajes rápidos","Missatges ràpids"),
"quick_ok": ("I'm OK","Estoy bien","Estic bé"),
"quick_ok_body": ("With your position","Con tu posición","Amb la teua posició"),
"quick_position": ("Send my position","Enviar mi posición","Enviar la meua posició"),
"quick_phrases": ("Stock phrases (Spanish, shortest on air)","Frases (ocupan menos tiempo en el aire)","Frases (en castellà, ocupen menys temps en l'aire)"),
"airtime": ("≈ %1$s · %2$s","≈ %1$s · %2$s","≈ %1$s · %2$s"),
"duration_s": ("%1$d s","%1$d s","%1$d s"),
"duration_min_s": ("%1$d min %2$d s","%1$d min %2$d s","%1$d min %2$d s"),
"via_pharos": ("via %1$s","vía %1$s","via %1$s"),
"auth_signed": ("Signed: sender verified","Firmado: remitente verificado","Signat: remitent verificat"),
"auth_encrypted": ("Encrypted: sender verified","Cifrado: remitente verificado","Xifrat: remitent verificat"),
"delivery_pending": ("Sending","Enviando","S'està enviant"),
"delivery_acked": ("Received","Recibido","Rebut"),
"delivery_failed": ("Not delivered","No entregado","No entregat"),
"emergency_encryption": ("Emergency encryption","Cifrado de emergencia","Xifratge d'emergència"),
"encrypt_all_on": ("On for every chat in Settings","Activado para todos los chats en Ajustes","Activat per a tots els xats en Configuració"),
"encrypted_banner": ("Emergency encryption is on in this chat","Cifrado de emergencia activado en este chat","Xifratge d'emergència activat en este xat"),
"encrypted_banner_all": ("Emergency encryption is on for every chat","Cifrado de emergencia activado en todos los chats","Xifratge d'emergència activat en tots els xats"),
"encrypt_confirm_title": ("Turn on emergency encryption?","¿Activar el cifrado de emergencia?","Activar el xifratge d'emergència?"),
"encrypt_confirm_body": ("Only in a real emergency or for tests into a dummy load: amateur radio rules forbid encryption in normal use. Without it, messages are signed: anyone can read them, nobody can forge them.","Úsalo solo en una emergencia real o en pruebas con carga ficticia: en radioafición no se permite cifrar en uso normal. Sin cifrar, los mensajes van firmados: cualquiera puede leerlos, pero nadie puede falsificarlos.","Fes-lo servir només en una emergència real o en proves amb càrrega fictícia: en radioafició no es permet xifrar en ús normal. Sense xifrar, els missatges van signats: qualsevol pot llegir-los, però ningú pot falsificar-los."),
"no_pharos_banner": ("No active Pharos: these messages will not go out","No hay un Pharos activo: estos mensajes no saldrán","No hi ha cap Pharos actiu: estos missatges no eixiran"),
"safety_number": ("Safety code","Código de seguridad","Codi de seguretat"),
"safety_number_body": ("Compare it with the one %1$s sees on their phone, in person or by phone. If they match, nobody is impersonating them.","Compáralo con el que %1$s ve en su teléfono, en persona o por teléfono. Si coincide, nadie se ha hecho pasar por él.","Compara'l amb el que %1$s veu en el seu telèfon, en persona o per telèfon. Si coincideix, ningú no s'ha fet passar per ell."),
"group_invite": ("Invite to the group","Invitar al grupo","Convidar al grup"),
"group_invite_warning": ("Whoever has this code joins the group and can read and write. Show it in person.","Quien tenga este código entra en el grupo y puede leer y escribir. Enséñalo en persona.","Qui tinga este codi entra en el grup i pot llegir i escriure. Mostra'l en persona."),
"group_share_confirm_title": ("Take the code off the phone?","¿Sacar el código del teléfono?","Traure el codi del telèfon?"),
# New chat
"contacts": ("Contacts","Contactos","Contactes"),
"contacts_empty": ("No contacts yet.","Aún no tienes contactos.","Encara no tens contactes."),
"groups": ("Groups","Grupos","Grups"),
"groups_empty": ("You are not in any group yet.","Aún no estás en ningún grupo.","Encara no estàs en cap grup."),
"scan_contact": ("Scan their QR code","Escanear su código QR","Escanejar el seu codi QR"),
"scan_group": ("Scan invitation","Escanear invitación","Escanejar invitació"),
"scan_meshcore": ("Scan a MeshCore code","Escanear código de MeshCore","Escanejar un codi de MeshCore"),
"meshcore_hint": ("In the MeshCore app, open the contact and show its QR code to share it. Only one-to-one chats: channels are not bridged.","En la app de MeshCore, abre el contacto y muestra su código QR para compartirlo. Solo chats individuales: los canales no se conectan.","En l'aplicació de MeshCore, obri el contacte i mostra el seu codi QR per a compartir-lo. Només xats individuals: els canals no es connecten."),
"group_create": ("Create group","Crear grupo","Crear grup"),
"group_create_body": ("A new key is made. Then invite each person by showing them the group code.","Se crea una clave nueva. Luego invita a cada persona enseñándole el código del grupo.","Es crea una clau nova. Després convida cada persona mostrant-li el codi del grup."),
"group_name": ("Group name","Nombre del grupo","Nom del grup"),
"pick_phone_contact": ("Choose from the phone's contacts","Elegir de los contactos del teléfono","Triar dels contactes del telèfon"),
"phone": ("Phone number","Número de teléfono","Número de telèfon"),
"phone_or_username": ("Signal number or username","Número o usuario de Signal","Número o usuari de Signal"),
"phone_help": ("With country code, e.g. +34 600 123 456","Con prefijo de país, p. ej. +34 600 123 456","Amb prefix de país, p. ex. +34 600 123 456"),
"name_optional": ("Name (optional)","Nombre (opcional)","Nom (opcional)"),
"open_chat": ("Open chat","Abrir chat","Obrir xat"),
"bridged_note": ("Your Pharos will write to them from its own number. Text only, up to 200 characters per message.","Tu Pharos le escribirá desde su número. Solo texto, hasta 200 caracteres por mensaje.","El teu Pharos li escriurà des del seu número. Només text, fins a 200 caràcters per missatge."),
# Import
"import_contact_title": ("Add %1$s?","¿Añadir a %1$s?","Afegir %1$s?"),
"import_pharos_title": ("Trust the Pharos %1$s?","¿Confiar en el Pharos %1$s?","Confiar en el Pharos %1$s?"),
"import_pharos_body": ("Callsign %1$s. You will use it to talk to Signal, WhatsApp and MeshCore.","Indicativo %1$s. Lo usarás para hablar con Signal, WhatsApp y MeshCore.","Indicatiu %1$s. El faràs servir per a parlar amb Signal, WhatsApp i MeshCore."),
"import_group_title": ("Join %1$s?","¿Unirte a %1$s?","Unir-te a %1$s?"),
"import_group_body": ("The group will be told over radio that you joined.","Avisaremos al grupo por radio de que te unes.","Avisarem el grup per ràdio que t'hi uneixes."),
"import_meshcore_contact": ("Open a chat with %1$s on MeshCore?","¿Abrir chat con %1$s de MeshCore?","Obrir un xat amb %1$s de MeshCore?"),
"import_meshcore_body": ("It goes through your active Pharos.","Irá a través de tu Pharos activo.","Anirà a través del teu Pharos actiu."),
# Pharos
"pharos_title": ("Pharos","Pharos","Pharos"),
"pharos_none": ("No active Pharos","Ningún Pharos activo","Cap Pharos actiu"),
"pharos_none_body": ("Add your home Pharos by scanning its QR code, or look for one nearby over radio.","Añade el Pharos de casa escaneando su código QR, o busca uno cercano por radio.","Afig el Pharos de casa escanejant el seu codi QR, o busca'n un de pròxim per ràdio."),
"pharos_indicator": ("Pharos %1$s: %2$s","Pharos %1$s: %2$s","Pharos %1$s: %2$s"),
"pharos_heard_now": ("Heard just now","Oído ahora mismo","Escoltat ara mateix"),
"pharos_never_heard": ("Not heard yet","Sin noticias todavía","Encara sense notícies"),
"pharos_snr": ("Hears you at %1$d dB","Te oye a %1$d dB","Et sent a %1$d dB"),
"pharos_trusted": ("Trusted Pharos","Pharos de confianza","Pharos de confiança"),
"pharos_trusted_empty": ("You don't trust any Pharos yet.","Aún no confías en ningún Pharos.","Encara no confies en cap Pharos."),
"pharos_manage": ("Trusted Pharos","Pharos de confianza","Pharos de confiança"),
"pharos_add_qr": ("Add with QR code","Añadir con código QR","Afegir amb codi QR"),
"pharos_add_qr_body": ("The safest way: scan it at home","La forma más segura: escanéalo en casa","La manera més segura: escaneja'l a casa"),
"pharos_discover": ("Look for nearby Pharos","Buscar Pharos cercanos","Buscar Pharos pròxims"),
"pharos_discover_body": ("Sends a query in the most robust mode and waits for answers","Envía una consulta en el modo más robusto y espera respuestas","Envia una consulta en el mode més robust i espera respostes"),
"pharos_found": ("Found","Encontrados","Trobats"),
"pharos_found_none": ("No Pharos answered.","Ningún Pharos ha respondido.","Cap Pharos ha respost."),
"pharos_found_detail": ("%1$d dB · %2$s","%1$d dB · %2$s","%1$d dB · %2$s"),
"pharos_access_open": ("Open to anyone","Abierto a cualquiera","Obert a qualsevol"),
"pharos_access_code": ("Needs an access code","Necesita código de acceso","Necessita codi d'accés"),
"pharos_access_closed": ("By invitation only","Solo con invitación","Només amb invitació"),
"pharos_already_trusted": ("Trusted","De confianza","De confiança"),
"pharos_tofu_note": ("A Pharos found over radio stays unverified. Compare its safety code with its operator when you can.","Un Pharos encontrado por radio queda sin verificar. Compara su código de seguridad con su operador cuando puedas.","Un Pharos trobat per ràdio queda sense verificar. Compara el seu codi de seguretat amb el seu operador quan pugues."),
"pharos_verified_body": ("Verified: its key matches its QR code, or you checked it by hand.","Verificado: su clave coincide con la de su código QR o la comprobaste a mano.","Verificat: la seua clau coincideix amb la del codi QR o la vas comprovar a mà."),
"pharos_unverified_body": ("Unverified: you found it over radio. Compare this code with its operator.","Sin verificar: lo encontraste por radio. Compara este código con su operador.","Sense verificar: el vas trobar per ràdio. Compara este codi amb el seu operador."),
"verified": ("Verified","Verificado","Verificat"),
"not_verified": ("Unverified","Sin verificar","Sense verificar"),
"mark_verified": ("Mark as verified","Marcar como verificado","Marcar com a verificat"),
"use_this_pharos": ("Use this Pharos","Usar este Pharos","Fer servir este Pharos"),
"access_code": ("Access code","Código de acceso","Codi d'accés"),
"access_code_body": ("Ask the Pharos operator for it. It never goes over the air: the app only sends a proof that you know it.","Pídeselo al operador del Pharos. Nunca sale por radio: la app solo envía una prueba de que lo conoces.","Demana'l a l'operador del Pharos. Mai no ix per ràdio: l'aplicació només envia una prova que el coneixes."),
"access_code_enter": ("Enter access code","Introducir código de acceso","Introduir codi d'accés"),
"ping_now": ("Check now","Comprobar ahora","Comprovar ara"),
"pinging": ("Checking…","Comprobando…","S'està comprovant…"),
"ping_ok": ("The Pharos answered","El Pharos ha respondido","El Pharos ha respost"),
"ping_silent": ("The Pharos does not answer","El Pharos no responde","El Pharos no respon"),
"ping_note": ("Each check is a short radio transmission. Only check when you need to.","Cada comprobación es una breve transmisión de radio. Hazlas solo cuando las necesites.","Cada comprovació és una transmissió de ràdio breu. Fes-les només quan les necessites."),
"ping_interval": ("Check the Pharos automatically","Comprobar el Pharos automáticamente","Comprovar el Pharos automàticament"),
"ping_off": ("Never (recommended)","Nunca (recomendado)","Mai (recomanat)"),
"registration_ok": ("Registered","Registrado","Registrat"),
"registration_pending": ("Registration waiting for approval","Registro pendiente de aprobación","Registre pendent d'aprovació"),
"registration_refused": ("Registration refused","Registro rechazado","Registre rebutjat"),
"registration_needs_code": ("Needs its access code","Necesita su código de acceso","Necessita el seu codi d'accés"),
"registration_none": ("Not registered","Sin registrar","Sense registrar"),
# My code
"my_code_body": ("Show it to whoever wants to write to you over radio, so they can scan it.","Enséñaselo a quien quiera escribirte por radio para que lo escanee.","Mostra'l a qui vulga escriure't per ràdio perquè l'escanege."),
"my_code_settings_body": ("So others can add you","Para que otros te añadan","Perquè altres t'afigen"),
# Settings
"settings_profile": ("Profile","Perfil","Perfil"),
"profile_note": ("People who already added you see the new name when they scan your code again.","Quien ya te tiene añadido verá el nombre nuevo cuando vuelva a escanear tu código.","Qui ja t'ha afegit veurà el nom nou quan torne a escanejar el teu codi."),
"settings_safety": ("Safety","Seguridad","Seguretat"),
"sos_contacts": ("Emergency contacts (SOS)","Contactos de emergencia (SOS)","Contactes d'emergència (SOS)"),
"sos_contacts_auto": ("Automatic: every group","Automático: todos los grupos","Automàtic: tots els grups"),
"sos_contacts_auto_body": ("Turn off to choose chats one by one","Desactívalo para elegir chats uno a uno","Desactiva-ho per a triar xats un a un"),
"sos_contacts_note": ("The SOS is never encrypted. To Signal, WhatsApp and MeshCore it goes through your Pharos.","El SOS nunca va cifrado. A Signal, WhatsApp y MeshCore llega a través de tu Pharos.","El SOS mai no va xifrat. A Signal, WhatsApp i MeshCore arriba a través del teu Pharos."),
"encrypt_all": ("Emergency encryption for every chat","Cifrado de emergencia en todos los chats","Xifratge d'emergència en tots els xats"),
"encrypt_all_body": ("Overrides each chat's setting. The SOS still goes unencrypted.","Anula el ajuste de cada chat. El SOS sigue yendo sin cifrar.","Anul·la la configuració de cada xat. El SOS continua anant sense xifrar."),
"encrypt_all_confirm_title": ("Encrypt every chat?","¿Cifrar todos los chats?","Xifrar tots els xats?"),
"settings_radio": ("Radio","Radio","Ràdio"),
"radio_disconnected": ("Radio not connected","Radio no conectada","Ràdio no connectada"),
"radio_audio_only": ("Radio connected, audio only: set the frequency on the radio","Radio conectada solo con audio: pon la frecuencia en la radio","Ràdio connectada només amb àudio: posa la freqüència en la ràdio"),
"radio_ready": ("Radio connected: audio and control","Radio conectada: audio y control","Ràdio connectada: àudio i control"),
"radio_states_help": ("Grey: no radio. Orange: audio only, you set the frequency by hand. Green: audio and CAT, the app sets everything.","Gris: sin radio. Naranja: solo audio, pones la frecuencia a mano. Verde: audio y CAT, la app lo ajusta todo.","Gris: sense ràdio. Taronja: només àudio, poses la freqüència a mà. Verd: àudio i CAT, l'aplicació ho ajusta tot."),
"radio_usb_note": ("Plug the radio into the phone's USB port (USB OTG). Radio support arrives in phase 2.","Conecta la radio al USB del teléfono (USB OTG). El soporte de radio llega en la fase 2.","Connecta la ràdio a l'USB del telèfon (USB OTG). El suport de ràdio arriba en la fase 2."),
"settings_radio_rig": ("Rig (QDX, G90)","Equipo (QDX, G90)","Equip (QDX, G90)"),
"settings_band": ("Band and speed","Banda y velocidad","Banda i velocitat"),
"settings_band_body": ("Automatic, by time of day and signal","Automáticas según la hora y la señal","Automàtiques segons l'hora i el senyal"),
"settings_backup": ("Backup","Copia de seguridad","Còpia de seguretat"),
"backup_export": ("Export backup","Exportar copia","Exportar còpia"),
"backup_export_body": ("Identity, contacts, groups, Pharos and settings, encrypted with a passphrase","Identidad, contactos, grupos, Pharos y ajustes, cifrados con una contraseña","Identitat, contactes, grups, Pharos i configuració, xifrats amb una contrasenya"),
"backup_restore": ("Restore backup","Restaurar copia","Restaurar còpia"),
"backup_restore_body": ("Replaces this phone's identity","Sustituye la identidad de este teléfono","Substituïx la identitat d'este telèfon"),
"backup_restore_warning": ("A clean start: this phone's identity, contacts and chats are replaced by the backup's, and message history is not restored. Never use the same identity on two phones at once.","Empiezas de cero: la identidad, los contactos y los chats de este teléfono se sustituyen por los de la copia, y el historial de mensajes no se recupera. No uses nunca la misma identidad en dos teléfonos a la vez.","Comences de zero: la identitat, els contactes i els xats d'este telèfon se substituïxen pels de la còpia, i l'historial de missatges no es recupera. No faces servir mai la mateixa identitat en dos telèfons alhora."),
"backup_passphrase_body": ("Keep it safe: without it the backup cannot be opened.","Guárdala bien: sin ella la copia no se puede abrir.","Guarda-la bé: sense ella la còpia no es pot obrir."),
"backup_saved": ("Backup saved","Copia guardada","Còpia guardada"),
"backup_restored": ("Backup restored","Copia restaurada","Còpia restaurada"),
"backup_wrong_passphrase": ("Wrong passphrase or damaged backup","Contraseña incorrecta o copia dañada","Contrasenya incorrecta o còpia danyada"),
"passphrase": ("Passphrase","Contraseña","Contrasenya"),
"passphrase_help": ("At least 8 characters","Al menos 8 caracteres","Almenys 8 caràcters"),
"settings_about": ("About","Acerca de","Quant a"),
"about_body": ("Version %1$s. HF radio messaging for everyone.","Versión %1$s. Mensajería por radio HF para todos.","Versió %1$s. Missatgeria per ràdio HF per a tothom."),
"about_source": ("Source code","Código fuente","Codi font"),
}
P = {
"frames": (("%d frame","%d frames"),("%d trama","%d tramas"),("%d trama","%d trames")),
"pharos_heard_min": (("Heard %d minute ago","Heard %d minutes ago"),("Oído hace %d minuto","Oído hace %d minutos"),("Escoltat fa %d minut","Escoltat fa %d minuts")),
"pharos_heard_h": (("Heard %d hour ago","Heard %d hours ago"),("Oído hace %d hora","Oído hace %d horas"),("Escoltat fa %d hora","Escoltat fa %d hores")),
"ping_every": (("Every %d minute","Every %d minutes"),("Cada %d minuto","Cada %d minutos"),("Cada %d minut","Cada %d minuts")),
"sos_contacts_count": (("%d chat chosen","%d chats chosen"),("%d chat elegido","%d chats elegidos"),("%d xat triat","%d xats triats")),
}
# Android plural categories: en one/other; es one/many/other; ca one/many/other.
CATS = {0: ["one","other"], 1: ["one","many","other"], 2: ["one","many","other"]}
DIRS = {0: "values", 1: "values-es", 2: "values-ca"}
NOTE = {0: "English (default)", 1: "Spanish", 2: "Valencian (Catalan)"}

import pathlib
from xml.sax.saxutils import escape

root = pathlib.Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
for i in range(3):
    out = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {NOTE[i]}. Generated from one table; keep the three files in step. -->', '<resources>']
    for k, v in S.items():
        attr = ' translatable="false"' if k == "app_name" and i == 0 else ""
        if k == "app_name" and i != 0:
            continue
        t = escape(v[i]).replace("'", "\\'")
        out.append(f'    <string name="{k}"{attr}>{t}</string>')
    for k, v in P.items():
        one, other = v[i]
        out.append(f'    <plurals name="{k}">')
        for c in CATS[i]:
            out.append(f'        <item quantity="{c}">{escape(one if c == "one" else other)}</item>')
        out.append('    </plurals>')
    out.append('</resources>')
    d = root / DIRS[i]
    d.mkdir(exist_ok=True)
    (d / "strings.xml").write_text("\n".join(out) + "\n")
    print(d / "strings.xml", len(S), "strings")
