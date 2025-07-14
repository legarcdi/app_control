
const express = require('express');
const receiptline = require('receiptline');
const escpos = require('escpos');
const path = require('path');
const fs = require('fs');
const os = require('os');

console.log('=== [Node.js] index.js iniciado en Android ===');
console.log('Plataforma:', os.platform(), 'CWD:', process.cwd(), 'DIRNAME:', __dirname);

//INICIO OCULTAMIENTO EN SEGUNDO PLANO WINDOWS
if (os.platform() === 'win32') {
  const hideConsoleWindow = require('node-hide-console-window');
// Oculta la consola al iniciar la aplicación
hideConsoleWindow.hideConsole();
} else {
  console.log('No se puede ocultar EL SO no es compatible.');
  // Agrega aquí el código para otros sistemas operativos
}
//FIN OCULTAMIENTO
//INICIO EMPAQUETAMIENDO LIBRERIA PARA IMPRIMIR USB EN WINDOWS
// Verifica si estás en un snapshot (empaquetado)
const isSnapshot = !!process.pkg;
// Ruta del archivo binario dentro del snapshot o en desarrollo
const binarySourcePath = isSnapshot
  ? path.join(__dirname, 'node_modules/usb/prebuilds/win32-x64/node.napi.node') // Cambia la ruta según la arquitectura de tu sistema
  : path.join(__dirname, 'node_modules/usb/prebuilds/win32-x64/node.napi.node');
// Ruta de destino donde el binario será extraído en tiempo de ejecución
const binaryTargetPath = path.join(process.cwd(), 'node.napi.node');
// Extrae el archivo si estás dentro de un snapshot
if (isSnapshot) {
  try {
    if (!fs.existsSync(binaryTargetPath)) {
      // Copiar el archivo binario desde el snapshot al directorio de trabajo
      fs.writeFileSync(binaryTargetPath, fs.readFileSync(binarySourcePath));
      console.log(`Archivo binario extraído en: ${binaryTargetPath}`);
    }
  } catch (error) {
    console.error('Error al extraer el archivo binario:', error);
  }
}
// Configura la variable de entorno para bindings
process.env.NODE_BINDINGS_PATH = path.dirname(binaryTargetPath);
//FIN EMPAQUETAMIENDO LIBRERIA PARA IMPRIMIR USB EN WINDOWS

const usb = require('escpos-usb');
const net = require('net');
const cors = require('cors');
const app = express();
const port = 3006;
escpos.USB = usb;
app.use(express.json({ limit: '4mb' }));
app.use(cors());
// Función para imprimir un pedido
async function printPedido(pedido) {
  try {
    const cuerpo = pedido.body;
    // Validaciones y logs
    console.log('Body recibido en printPedido:', JSON.stringify(cuerpo, null, 2));
    if (!cuerpo) throw new Error('No se recibió el cuerpo del pedido');

    // Validar arrays
    const first_lines = Array.isArray(cuerpo.first_lines) ? cuerpo.first_lines : [];
    const last_lines = Array.isArray(cuerpo.last_lines) ? cuerpo.last_lines : [];
    const productos = Array.isArray(cuerpo.productos) ? cuerpo.productos : [];
    const datos_ped = Array.isArray(cuerpo.datos_ped) ? cuerpo.datos_ped : [{}];

    const imagen = cuerpo.imagen;
    const tipo_impresora = cuerpo.tipo_impresora;
    const num_impresiones = cuerpo.num_copias;

    console.log('Tipo de impresora recibido:', tipo_impresora);
    const template = `{image:${imagen}}\n
    ${first_lines.join('\n')}
    {width:*,15}
    Pedido  | "${datos_ped[0].folio || ''}"
    ${datos_ped[0].fecha || ''}   |
    ${datos_ped[0].estado || ''}   |
    {width:*,20}
    Produce: ${datos_ped[0].produce || ''} | Entrega: ${datos_ped[0].entrega || ''}
    {width:*,1}
    Captura: ${datos_ped[0].captura || ''} |
    Cliente: ${datos_ped[0].cliente || ''} |
    Dirección: ${datos_ped[0].direccion || ''} |
    Comentarios: "${datos_ped[0].comentarios || ''}" |
    {border:space; width:6,*,8,8}
    "Cant." |"Producto" |"Precio"|"Total"
    ${productos.map(producto => {
      let linea = `${producto.cantidad} |${producto.nombre} | ${producto.p_unit}| ${producto.total}`;
      if (producto.descuento > 0) {
        linea += `\n  "" |Dto.: ${producto.descuento}`;
      }
      return linea;
    }).join('\n')}
    -------------------------------------
    {width:*,20}
    "TOTAL"             |          "${datos_ped[0].total_nota || ''}"
    {width:48}
    ${last_lines.join('\n')}
    `;

    const printerOptions = {
      "cpl": cuerpo.num_car,
      "encoding": "multilingual",
      "spacing": cuerpo.line_height,
      "command": cuerpo.m_impresora
    };

    const receipt = Buffer.from(receiptline.transform(template, printerOptions), 'binary');

    if (tipo_impresora === "usb") {
      console.log("Entrando a impresión USB");
      const device = new escpos.USB();
      const printer = new escpos.Printer(device);

      device.open(() => {
        for (let i = 0; i < num_impresiones; i++) {
          printer.raw(receipt);
        }
        printer.close();
      });
    } else if (tipo_impresora === "red") {
      console.log("Entrando a impresión RED");
      const PRINTER_IP = cuerpo.ip_impresora;
      const PRINTER_PORT = 9100;
      const client = new net.Socket();
      client.connect(PRINTER_PORT, PRINTER_IP, () => {
        for (let i = 0; i < num_impresiones; i++) {
          client.write(receipt);
        }
        client.end();
      });
    } else if (tipo_impresora === "bluetooth") {
      console.log("Entrando a impresión BLUETOOTH");
      // Guardar el recibo en un archivo para que Android lo imprima por Bluetooth
      const storagePath = process.env.NODEJS_MOBILE_APP_STORAGE_PATH || __dirname;
      const printDir = storagePath; // Corregido: ya es .../files/nodejs-project
      const printPath = path.join(printDir, 'print_bt.txt');
      try {
        // Asegúrate de crear el directorio si no existe
        fs.mkdirSync(printDir, { recursive: true });
        console.log('Intentando escribir print_bt.txt en:', printPath);
        console.log('Contenido a escribir (bytes):', receipt.length);
        fs.writeFileSync(printPath, receipt);
        console.log('fs.writeFileSync ejecutado');
        // Validar que el archivo existe y tiene contenido
        if (fs.existsSync(printPath)) {
          const stats = fs.statSync(printPath);
          console.log(`Archivo para impresión Bluetooth generado: ${printPath} (${stats.size} bytes)`);
          // Listar archivos en el directorio para depuración
          const files = fs.readdirSync(path.dirname(printPath));
          console.log('Archivos en el directorio:', files);
        } else {
          console.error('Error: print_bt.txt no se creó correctamente en', printPath);
        }
      } catch (err) {
        console.error('Error escribiendo print_bt.txt:', err);
      }
    } else {
      console.log("Tipo de impresora desconocido:", tipo_impresora);
    }
  } catch (error) {
    console.error('Error al imprimir:', error);
  }
}
async function printRemision(remision) {
  try {
    //const { items, total, payment, change } = req.body;
    const cuerpo = remision.body;
    const imagen = cuerpo.imagen;
    const tipo_impresora = cuerpo.tipo_impresora
    const num_impresiones = cuerpo.num_copias;

    console.log('Tipo de impresora recibido:', tipo_impresora);
    // Ejemplo de nota de venta
    const template = `{image:${imagen}}\n
${cuerpo.first_lines.join('\n')}
{width:*,15}
Venta ${cuerpo.datos_rem[0].tipo_venta} | "${cuerpo.datos_rem[0].folio}"
${cuerpo.datos_rem[0].fecha}   |
{width:*,1}
Vendedor: ${cuerpo.datos_rem[0].vendedor} |
Cliente: ${cuerpo.datos_rem[0].cliente} |
{border:space; width:6,*,8,8}
"Cant." |"Producto" |"Precio"|"Total"
${cuerpo.productos
  .map(producto => {
    let linea = `${producto.cantidad} |${producto.nombre} | ${producto.p_unit}| ${producto.total}`;
    if (producto.descuento > 0) {
      linea += `\n  "" |Dto.: ${producto.descuento}`;
    }
    return linea;
  })
  .join('\n')}
-------------------------------------
{width:*,20}
"TOTAL"             |          "${cuerpo.datos_rem[0].total_nota}"
{width:48}
${cuerpo.last_lines.join('\n')}
`;
    // Convertir a comandos ESC/POS
    const printerOptions = {
      //cpl: 48, // Caracteres por línea
      //encoding: 'generic', // Codificación común en impresoras térmicas
      //"cpl": 48,
      "cpl": cuerpo.num_car,
      "encoding": "multilingual",
      "spacing": cuerpo.line_height,
      //"command": "generic"
      "command": cuerpo.m_impresora
  };
const receipt = Buffer.from(receiptline.transform(template, printerOptions), 'binary');
            if (tipo_impresora === "usb") {
              console.log("Entrando a impresión USB");
              try {
                    //USB
                    // Enviar comandos a la impresora USB
                    const device = new escpos.USB(); // Usar dispositivo USB // El constructor buscará automáticamente la primera impresora compatible
                    // Si necesitas identificar una impresora específica, busca el identificador en `lsusb` (en Linux).
                    // Configura el VID y PID de la impresora
                    //const device = new escpos.USB(0x1234, 0x5678); // Reemplaza con el Vendor ID y Product ID de tu impresora
                    //en windows se podrian adicionar las siguientes paquetes si en dado caso no encuentra la impresora:
                    //npm install --global --production windows-build-tools
                    const printer = new escpos.Printer(device);
                    //variable para saber el numero de veces que se imprimirá por default ahora es uno
                    device.open(() => {
                      for (let i = 0; i < num_impresiones; i++) {
                        printer
                          .raw(receipt);
                        }
                        //.cut()
                        printer.close();  // Cierra la conexión con la impresora
                    });
                      //res.status(200).send({ message: 'Nota de venta enviada a la impresora.' });
                } catch (error) {
                  console.error('Printing error:', error);
                }
              //FIN USB
            } else if (tipo_impresora === "red") {
            console.log("Entrando a impresión RED");
            //IP
                const PRINTER_IP = cuerpo.ip_impresora; // Cambia esto por la IP de tu impresora
                //const PRINTER_IP = '192.168.1.250'; // Cambia esto por la IP de tu impresora
                const PRINTER_PORT = 9100; // El puerto por defecto para impresoras de red
                  // Enviar el recibo a la impresora mediante socket TCP
                const client = new net.Socket();

                client.connect(PRINTER_PORT, PRINTER_IP, () => {
                    console.log('Conectado a la impresora.');
                    for (let i = 0; i < num_impresiones; i++) {
                    client.write(receipt); // Enviar el recibo
                  }
                    client.end();
                });

                client.on('error', (err) => {
                    console.error('Error al conectar con la impresora:', err.message);
                });

                client.on('close', () => {
                    console.log('Conexión con la impresora cerrada.');
                });
            //FIN IP
            } else if (tipo_impresora === "bluetooth") {
              console.log("Entrando a impresión BLUETOOTH");
              // Guardar el recibo en un archivo para que Android lo imprima por Bluetooth
              const storagePath = process.env.NODEJS_MOBILE_APP_STORAGE_PATH || __dirname;
              const printDir = storagePath; // Corregido: ya es .../files/nodejs-project
              const printPath = path.join(printDir, 'print_bt.txt');
              try {
                fs.mkdirSync(printDir, { recursive: true });
                console.log('Intentando escribir print_bt.txt en:', printPath);
                console.log('Contenido a escribir (bytes):', receipt.length);
                fs.writeFileSync(printPath, receipt);
                console.log('fs.writeFileSync ejecutado');
                if (fs.existsSync(printPath)) {
                  const stats = fs.statSync(printPath);
                  console.log(`Archivo para impresión Bluetooth generado: ${printPath} (${stats.size} bytes)`);
                  const files = fs.readdirSync(path.dirname(printPath));
                  console.log('Archivos en el directorio:', files);
                } else {
                  console.error('Error: print_bt.txt no se creó correctamente en', printPath);
                }
              } catch (err) {
                console.error('Error escribiendo print_bt.txt:', err);
              }
            } else {
            console.log("Tipo de impresora desconocida.");
            }
        }
        catch (error) {
          console.error('Error al imprimir:', error);
          throw error;

        }
}
// Endpoint para agregar pedidos a la cola
app.post('/print_pedido', (req, res) => {
  console.log('=== [Node.js] POST /print_pedido recibido ===');
  console.log('Body recibido:', req.body);

  // Cola de solicitudes
  let printQueue = [];
  let isPrinting = false;

  // Función para procesar la cola de impresión
  async function processQueue() {
    if (isPrinting || printQueue.length === 0) {
      return; // Si ya estamos imprimiendo o la cola está vacía, no hacemos nada
    }

    isPrinting = true; // Indicamos que estamos imprimiendo

    // Tomamos el siguiente pedido de la cola
    const pedido = printQueue.shift();
    try {
      await printPedido(pedido); // Imprimir el pedido
    } catch (error) {
      console.error('Error durante la impresión de pedido:', error);
    } finally {
      isPrinting = false; // Indicamos que ya hemos terminado de imprimir
      processQueue(); // Continuamos con el siguiente pedido en la cola
    }
  }
  const pedido = req.body;
  printQueue.push({ body: pedido });
  res.json({ message: 'Pedido agregado a la cola de impresión.' });
  processQueue(); // Iniciar el procesamiento de la cola
});

// Nuevo endpoint '/print' para manejar las ventas
app.post('/print', (req, res) => {
  console.log('=== [Node.js] POST /print recibido ===');
  console.log('[Node.js] Body recibido en /print:', JSON.stringify(req.body, null, 2));
  // Cola de solicitudes
  let printQueueVentas = [];
  let isPrintingVenta = false;

  // Función para procesar la cola de impresión
  async function processQueueVenta() {
    if (isPrintingVenta || printQueueVentas.length === 0) {
      return; // Si ya estamos imprimiendo o la cola está vacía, no hacemos nada
    }

    isPrintingVenta = true; // Indicamos que estamos imprimiendo

    // Tomamos el siguiente pedido de la cola
    const remision = printQueueVentas.shift();
    try {
      await printRemision(remision); // Imprimir el pedido
    } catch (error) {
      console.error('Error durante la impresión de venta:', error);
    } finally {
      isPrintingVenta = false; // Indicamos que ya hemos terminado de imprimir
      processQueueVenta(); // Continuamos con el siguiente pedido en la cola
    }
  }
  const remision = req.body;
  printQueueVentas.push({ body: remision });
  res.json({ message: 'Nota de venta agregada a la cola de impresión.' });
  console.log('[Node.js] Nota de venta agregada a la cola de impresión.');
  processQueueVenta(); // Iniciar el procesamiento de la cola
});

// Endpoint de depuración para listar archivos en el directorio del proyecto
app.get('/debug-list-files', (req, res) => {
  try {
    const files = fs.readdirSync(__dirname);
    res.json({ files });
  } catch (err) {
    res.status(500).json({ error: err.toString() });
  }
});

app.listen(port, '0.0.0.0', () => {
  //  console.log(`Servidor escuchando en http://localhost:${port}`);
  console.log('=== [Node.js] Servidor Express escuchando en http://localhost:' + port + ' ===');
});