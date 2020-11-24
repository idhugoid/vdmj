#
# Start an LSP server
#

VERSION=4.4.1-SNAPSHOT
JAVA64_VMOPTS="-Xmx3000m -Xss5m -Djava.rmi.server.hostname=localhost -Dcom.sun.management.jmxremote"

if [ $# -ne 1 ]
then
    echo "Usage: lsp.sh [-vdmsl | -vdmpp | -vdmrt]"
    exit 1
else
    case $1 in
	-vdmsl|-vdmpp|-vdmrt)
	;;

	*)
	echo "Usage: lsp.sh [-vdmsl | -vdmpp | -vdmrt]"
	exit 1
	;;
    esac
fi

function latest()
{
    ls -t $1 | head -1
}

java ${JAVA64_VMOPTS} -Dlog.filename=/dev/tty \
    -cp \
$(latest "Annotations/target/annotations-${VERSION}-*.jar"):\
$(latest "FJ-VDMJ4/target/vdmj-${VERSION}-??????.jar"):\
$(latest "LSP/target/lsp-${VERSION}-*.jar") \
    lsp.LSPServerSocket $1 -lsp 8000 -dap 8001

exit 0

