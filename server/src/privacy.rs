pub const EN: &str = r#"<p class="meta">Last updated 18 October 2026 · <a href="/privacy/pt">Português</a></p>
<p>Nxtes is a handwriting and PDF app made by Aallyn, an independent developer in Portugal. This policy covers the Nxtes Android app and the sync server at nxtes.mildhope.net. Questions go to <a href="mailto:nxtes@mildhope.net">nxtes@mildhope.net</a>.</p>

<h2>Without an account</h2>
<p>Your documents, notes, recordings and settings stay on your device. Nxtes has no ads, no analytics and no tracking, and it doesn't sell or share your data.</p>

<h2>Handwriting recognition</h2>
<p>Turning writing into text uses Google's ML Kit, which runs on your device: your writing isn't sent anywhere. The first time you use it, the app downloads the recognition model for your language from Google, and ML Kit sends Google basic diagnostics such as the configured language, device and app details and performance data, as described in <a href="https://developers.google.com/ml-kit/android-data-disclosure">Google's ML Kit disclosure</a>.</p>

<h2>Sync accounts</h2>
<p>Sync is optional. If you create an account, the server stores:</p>
<ul>
<li>your email address and a salted Argon2 hash of your password, never the password itself;</li>
<li>the names of your signed-in devices and when they were last seen;</li>
<li>the documents, folders, pages, writing, recordings, templates, fonts and stickers you sync, plus earlier versions of them for 30 days;</li>
<li>the PDFs you choose to share as links, which anyone with the link can open until you remove the link or it expires.</li>
</ul>
<p>Your email address is used only to confirm it, to reset your password and to confirm a change of address. Everything travels over HTTPS and is stored on a server run by the developer in Portugal, with nightly backups kept for 30 days. Synced files aren't end-to-end encrypted, so whoever runs the server can technically access them. You can run your own Nxtes server instead.</p>
<p>The web proxy in front of the server logs IP addresses and the addresses requested, and keeps those logs for 14 days to keep the service working and secure.</p>

<h2>Deleting your data</h2>
<p>Delete your account in the app under Settings, Sync, Delete account, or on the web at <a href="/delete-account">nxtes.mildhope.net/delete-account</a>. This removes your account, synced documents, version history and share links from the server straight away, and the copies in nightly backups are gone within 30 days. Uninstalling the app removes everything stored on your device.</p>

<h2>Your rights</h2>
<p>Under the GDPR you can ask to see, correct, export or delete your data, or object to how it's used. Settings, Backup exports your whole library; for anything else, write to the address above. You can also complain to the Portuguese data protection authority, the <a href="https://www.cnpd.pt">CNPD</a>.</p>

<h2>Children</h2>
<p>Nxtes isn't directed at children under 13.</p>

<h2>Changes</h2>
<p>If this policy changes, the new version is posted here with a new date.</p>"#;

pub const PT: &str = r#"<p class="meta">Última atualização a 18 de outubro de 2026 · <a href="/privacy">English</a></p>
<p>O Nxtes é uma app de escrita à mão e PDF feita por Aallyn, um programador independente em Portugal. Esta política abrange a app Nxtes para Android e o servidor de sincronização em nxtes.mildhope.net. Questões para <a href="mailto:nxtes@mildhope.net">nxtes@mildhope.net</a>.</p>

<h2>Sem conta</h2>
<p>Os seus documentos, notas, gravações e definições ficam no seu dispositivo. O Nxtes não tem anúncios, análises nem rastreio, e não vende nem partilha os seus dados.</p>

<h2>Reconhecimento de escrita</h2>
<p>Converter escrita em texto usa o ML Kit da Google, que funciona no seu dispositivo: a sua escrita não é enviada para lado nenhum. Na primeira utilização, a app transfere da Google o modelo de reconhecimento do seu idioma, e o ML Kit envia à Google diagnósticos básicos, como o idioma configurado, dados do dispositivo e da app e dados de desempenho, conforme descrito na <a href="https://developers.google.com/ml-kit/android-data-disclosure">divulgação do ML Kit da Google</a>.</p>

<h2>Contas de sincronização</h2>
<p>A sincronização é opcional. Se criar uma conta, o servidor guarda:</p>
<ul>
<li>o seu endereço de email e um hash Argon2 com sal da sua palavra-passe, nunca a palavra-passe em si;</li>
<li>os nomes dos dispositivos com sessão iniciada e quando foram vistos pela última vez;</li>
<li>os documentos, pastas, páginas, escrita, gravações, modelos, tipos de letra e autocolantes que sincroniza, e versões anteriores durante 30 dias;</li>
<li>os PDFs que decide partilhar por link, que qualquer pessoa com o link pode abrir até remover o link ou este expirar.</li>
</ul>
<p>O seu endereço de email serve apenas para o confirmar, para repor a palavra-passe e para confirmar uma mudança de endereço. Tudo circula por HTTPS e fica guardado num servidor gerido pelo programador em Portugal, com cópias de segurança noturnas mantidas durante 30 dias. Os ficheiros sincronizados não têm encriptação ponto a ponto, por isso quem gere o servidor pode tecnicamente aceder-lhes. Pode, em alternativa, usar o seu próprio servidor Nxtes.</p>
<p>O proxy web à frente do servidor regista endereços IP e os endereços pedidos, e guarda esses registos durante 14 dias para manter o serviço a funcionar e seguro.</p>

<h2>Eliminar os seus dados</h2>
<p>Elimine a sua conta na app em Definições, Sincronização, Eliminar conta, ou na web em <a href="/delete-account">nxtes.mildhope.net/delete-account</a>. Isto remove de imediato do servidor a sua conta, os documentos sincronizados, o histórico de versões e os links de partilha, e as cópias nas cópias de segurança noturnas desaparecem em 30 dias. Desinstalar a app remove tudo o que está guardado no seu dispositivo.</p>

<h2>Os seus direitos</h2>
<p>Ao abrigo do RGPD, pode pedir para ver, corrigir, exportar ou eliminar os seus dados, ou opor-se à forma como são usados. Definições, Cópia de segurança exporta toda a sua biblioteca; para o resto, escreva para o endereço acima. Pode também apresentar queixa à <a href="https://www.cnpd.pt">CNPD</a>, a autoridade portuguesa de proteção de dados.</p>

<h2>Crianças</h2>
<p>O Nxtes não se destina a crianças com menos de 13 anos.</p>

<h2>Alterações</h2>
<p>Se esta política mudar, a nova versão é publicada aqui com uma nova data.</p>"#;
