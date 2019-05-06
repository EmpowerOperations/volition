
Param(
    $OutputCerts = "./sslcerts",
    $ServerCN = "localhost",
    $ClientCN = "localhost"
)

Get-Command "openssl" -ErrorAction Stop

# openSSL uses std-err like many unix apps as an INFO channel, 
# so tell powershell to interpret that output as such. 
$ErrorActionPreference = "SilentlyContinue" 

mkdir -p $OutputCerts
pushd $OutputCerts

echo "Generate CA key:"
openssl genrsa -out ca.key 4096

echo "Generate CA certificate:"
# Generates ca.crt which is the trustCertCollectionFile
openssl req -new -x509 -days 365 -key ca.key -out ca.crt -subj "/CN=$ServerCN"

echo "Generate server key:"
openssl genrsa -out server.key 4096

echo "Generate server signing request:"
openssl req -new -key server.key -out server.csr -subj "/CN=$ServerCN"

echo "Self-signed server certificate:"
# Generates server.crt which is the certChainFile for the server
openssl x509 -req -days 365 -in server.csr -CA ca.crt -CAkey ca.key -set_serial 01 -out server.crt

echo "Remove passphrase from server key:"
openssl rsa -in server.key -out server.key

echo "Generate client key"
openssl genrsa -out client.key 4096

echo "Generate client signing request:"
openssl req -new -key client.key -out client.csr -subj "/CN=${CLIENT_CN}"

echo "Self-signed client certificate:"
# Generates client.crt which is the clientCertChainFile for the client (need for mutual TLS only)
openssl x509 -req -days 365 -in client.csr -CA ca.crt -CAkey ca.key -set_serial 01 -out client.crt

echo "Remove passphrase from client key:"
openssl rsa -passin pass:1111 -in client.key -out client.key

echo "Converting the private keys to X.509:"
# Generates client.pem which is the clientPrivateKeyFile for the Client (needed for mutual TLS only)
openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pem

# Generates server.pem which is the privateKeyFile for the Server
openssl pkcs8 -topk8 -nocrypt -in server.key -out server.pem

popd