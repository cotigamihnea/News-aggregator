# News Aggregator

---

# Strategia de Paralelizare (Arhitectura Multi-Fază)

Pentru a depăși limitările de viteză ale I/O-ului secvenţial din implementarea inițială, am adoptat o arhitectură bazată pe trei faze, utilizând același set fix de workeri pe tot parcursul execuției.

## Arhitectura şi Etapele de Execuție

### 1. Etapa 1: Map (Procesare Paralelă)
* **Scop:** Citirea și parsarea articolelor, precum și pre-calculul cuvintelor cheie.
* **Mecanism:** Thread-urile Worker preiau căile fişierelor (utilizând un `AtomicInteger` pentru work-stealing) și procesează local fiecare articol.
* **Agregare:** La finalul citirii, fiecare worker varsă rezultatele locale în Aggregator-ul global printr-o singură metodă sincronizată (`combine`). Acest lucru minimizează contingența pe structurile globale.

### 2. Bariera (Sincronizare Intermediară)
* **Mecanism:** Toți workerii apelează `barrier.await()`.
* **Rol:** Aceasta asigură că toate articolele au fost procesate și agregate înainte de a trece la scrierea rezultatelor.

### 3. Etapa 2: Reduce & Write (I/O Paralel)
* **Scop:** Paralelizarea operațiilor de sortare finală și scrierea pe disc (cel mai mare bottleneck).
* **Pregătire (Thread 0):** Un singur thread (ID 0) calculează lista finală de articole unice și umple o coadă partajată (`ConcurrentLinkedQueue<String>`) cu task-uri de scriere ("lang:english", "reports").
* **Execuție (toți Workerii):** Workerii reintra în bucla de lucru, preluând task-uri de scriere din coadă.
    * Astfel, operațiile de sortare per fişier (pentru categorii/limbi) şi scrierea pe disc se desfășoară în paralel, distribuind sarcina I/O pe toate core-urile disponibile.

---

## Structuri de Date și Sincronizare

* **`AtomicInteger`**: Pentru distribuția dinamică a fişierelor JSON între workerii din Faza 1.
* **`CyclicBarrier`**: Mecanismul esențial de sincronizare, care permite thread-urilor să treacă de la faza de citire la cea de scriere fără a fi distruse și recreate.
* **`ConcurrentLinkedQueue<String>`**: Folosit ca o coadă de sarcini (task queue) în Faza 3, permițând tuturor workerilor să preia sarcini de scriere în paralel.
* **Hărţi Standard (`HashMap`)**: Utilizate în agregarea locală (în worker) pentru performanță maximă, fiind accesate o singură dată la final prin metoda `combine` sincronizată.

---

Pentru mai multe informații puteți consulta README.pdf.