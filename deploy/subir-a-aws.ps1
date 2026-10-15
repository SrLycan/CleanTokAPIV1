<#
  Sube el backend al VPS de AWS y lo instala o actualiza (desde Windows, con el ssh/scp/tar que ya trae Windows 11).

    .\deploy\subir-a-aws.ps1 -Ip 3.85.10.20 -Llave C:\Users\yo\Downloads\cleantok.pem
    .\deploy\subir-a-aws.ps1 -Ip 3.85.10.20 -Llave ...\cleantok.pem -Dominio api.midominio.com

  -Usuario: "ubuntu" en las AMI de Ubuntu (por defecto), "admin" en Debian.
#>
param(
    [Parameter(Mandatory = $true)] [string] $Ip,
    [Parameter(Mandatory = $true)] [string] $Llave,
    [string] $Usuario = "ubuntu",
    [string] $Dominio = ""
)
# ssh, scp y tar escriben avisos en stderr: se revisa $LASTEXITCODE en vez de detenerse con ellos.
$ErrorActionPreference = "Continue"
$raiz = Split-Path -Parent $PSScriptRoot
$paquete = Join-Path $env:TEMP "cleantok-backend.tgz"

# ssh rechaza la llave si otros usuarios de Windows pueden leerla.
icacls $Llave /inheritance:r /grant:r "$($env:USERNAME):(R)" | Out-Null

Write-Host "==> Empaquetando $raiz"
tar -czf $paquete -C $raiz --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=.idea --exclude=.env `
    --exclude=almacenamiento --exclude=respaldos --exclude=qr-hoja-prueba.pdf .
if ($LASTEXITCODE -ne 0) { throw "No se pudo empaquetar" }

$destino = "$Usuario@$Ip"
$opciones = @("-i", $Llave, "-o", "StrictHostKeyChecking=accept-new")

Write-Host "==> Subiendo a $destino"
scp @opciones $paquete "${destino}:/tmp/cleantok-backend.tgz"
if ($LASTEXITCODE -ne 0) { throw "scp falló: revisa la IP, la llave y que el grupo de seguridad permita SSH (22)" }

$dom = if ($Dominio) { "DOMINIO=$Dominio " } else { "" }
Write-Host "==> Instalando (la primera vez tarda 5-10 minutos)"
ssh @opciones $destino "mkdir -p ~/cleantok && tar -xzf /tmp/cleantok-backend.tgz -C ~/cleantok && sed -i 's/\r$//' ~/cleantok/deploy/*.sh ~/cleantok/gradlew && ${dom}bash ~/cleantok/deploy/instalar-vps.sh"
if ($LASTEXITCODE -ne 0) { throw "La instalación falló (mira el mensaje de arriba)" }
Remove-Item $paquete
