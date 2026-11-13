package com.casl.mp3converter.saas.api;

import com.casl.mp3converter.saas.billing.BillingRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Fase 9 (prueba manual) — PÁGINA de checkout del gateway MOCK, servida por el
 * propio backend. Simula el "checkout hosted" de Stripe/MercadoPago: el
 * usuario paga AQUÍ y la página dispara el webhook de confirmación
 * ({@code POST /api/payments/webhook}), igual que haría la pasarela real.
 * Jamás se pide ni se transmite un dato de tarjeta (regla del proyecto).
 */
@RestController
public class MockCheckoutPageController {

    private final BillingRepository billing;

    public MockCheckoutPageController(BillingRepository billing) {
        this.billing = billing;
    }

    /** Info pública del pago para que la página muestre el plan real. */
    @GetMapping("/mock-checkout/{externalId}/info")
    public ResponseEntity<?> info(@PathVariable String externalId) {
        var payment = billing.paymentByExternalId(externalId)
                .orElse(null);
        if (payment == null) {
            return ResponseEntity.status(404).body(Map.of("error", "pago no encontrado"));
        }
        var plan = billing.planById(payment.planId()).orElse(null);
        if (plan == null) {
            return ResponseEntity.status(404).body(Map.of("error", "plan no encontrado"));
        }
        return ResponseEntity.ok(Map.of(
                "planCode", plan.code(),
                "period", plan.period(),
                "amountCents", payment.amountCents(),
                "currency", payment.currency(),
                "status", payment.status()));
    }

    @GetMapping(value = "/mock-checkout/{externalId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String externalId) {
        // externalId saneado: sólo [A-Za-z0-9-] (evita inyectar HTML/JS):
        String id = externalId == null ? "" : externalId.replaceAll("[^A-Za-z0-9-]", "");
        if (id.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    "<html><body><p>Identificador de pago invalido.</p></body></html>");
        }
        return ResponseEntity.ok(html(id));
    }

    private static String html(String externalId) {
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                <meta charset="utf-8"/>
                <title>MP3Converter Pro — Checkout (mock)</title>
                <style>
                  body { font-family: sans-serif; background:#f4f5f7; display:flex;
                         justify-content:center; align-items:center; height:100vh; margin:0; }
                  .card { background:#fff; border-radius:12px; padding:32px; max-width:420px;
                          box-shadow:0 4px 20px rgba(0,0,0,.12); }
                  h1 { font-size:20px; margin-top:0; }
                  .plan { background:#eef3ff; border-radius:8px; padding:12px; margin:16px 0; }
                  .precio { font-size:24px; font-weight:bold; }
                  button { width:100%%; padding:12px; font-size:16px; border:0; border-radius:8px;
                           background:#2b6cb0; color:#fff; cursor:pointer; }
                  button:disabled { background:#9aa4b2; cursor:default; }
                  .nota { font-size:12px; color:#777; margin-top:14px; }
                  #resultado { margin-top:14px; font-weight:bold; }
                </style>
                </head>
                <body>
                <div class="card">
                  <h1>Suscripci&oacute;n MP3Converter Pro</h1>
                  <div class="plan">
                    Plan: <b id="nombrePlan">...</b><br/>
                    <span class="precio" id="precioPlan">...</span>
                  </div>
                  <button id="pagar" onclick="pagarMock()">Pagar (mock)</button>
                  <div id="resultado"></div>
                  <p class="nota">Pasarela MOCK del portfolio: NO se piden datos de
                  tarjeta. Al pagar se dispara el webhook que activa tu
                  suscripci&oacute;n; despu&eacute;s regresa a la app y pulsa
                  &laquo;Ya complet&eacute; el pago&raquo;.</p>
                </div>
                <script>
                const EXTERNAL_ID = '%s';

                function pesos(centavos) {
                  return '$' + (centavos / 100).toFixed(2);
                }

                async function cargarPlan() {
                  try {
                    const res = await fetch('/mock-checkout/' + encodeURIComponent(EXTERNAL_ID) + '/info');
                    if (!res.ok) {
                      document.getElementById('resultado').textContent =
                          'Pago no encontrado: vuelve a iniciar el checkout desde la app.';
                      return;
                    }
                    const info = await res.json();
                    document.getElementById('nombrePlan').textContent =
                        info.planCode + ' (' + info.period + ')';
                    document.getElementById('precioPlan').textContent =
                        pesos(info.amountCents) + ' ' + info.currency + ' / ' + info.period;
                    if (info.status !== 'PENDING') {
                      document.getElementById('pagar').disabled = true;
                      document.getElementById('pagar').textContent = 'Pagado';
                      document.getElementById('resultado').style.color = '#2f855a';
                      document.getElementById('resultado').textContent =
                          '\\u2714 Pago confirmado. Regresa a la app y pulsa '
                          + '\\u00abYa complet\\u00e9 el pago\\u00bb.';
                    }
                  } catch (e) {
                    document.getElementById('resultado').textContent = 'Error de red: ' + e;
                  }
                }

                async function pagarMock() {
                  const btn = document.getElementById('pagar');
                  const out = document.getElementById('resultado');
                  btn.disabled = true;
                  btn.textContent = 'Procesando...';
                  try {
                    const res = await fetch('/api/payments/webhook', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ externalId: EXTERNAL_ID, status: 'PAID' })
                    });
                    if (res.ok) {
                      out.style.color = '#2f855a';
                      out.textContent = '\\u2714 Pago confirmado. Regresa a la app y pulsa '
                          + '\\u00abYa complet\\u00e9 el pago\\u00bb.';
                      btn.textContent = 'Pagado';
                    } else {
                      out.style.color = '#c53030';
                      out.textContent = 'El pago no pudo confirmarse (' + res.status + ').';
                      btn.disabled = false;
                      btn.textContent = 'Reintentar';
                    }
                  } catch (e) {
                    out.style.color = '#c53030';
                    out.textContent = 'Error de red: ' + e;
                    btn.disabled = false;
                    btn.textContent = 'Reintentar';
                  }
                }

                cargarPlan();
                </script>
                </body>
                </html>
                """.formatted(externalId);
    }
}
