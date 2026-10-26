package app

import "time"

// Game output arrives in bursts (a modded start prints thousands of lines in a
// few seconds). Sending one event per line overflowed the event queue and
// dropped most of them; lines are now collected and sent every 80 ms.
const logBatch = 400

func (a *App) logLine(l string) {
	a.logMu.Lock()
	a.logPend = append(a.logPend, l)
	arm := !a.logArmed
	a.logArmed = true
	a.logMu.Unlock()
	if arm {
		time.AfterFunc(80*time.Millisecond, a.flushLogs)
	}
}

func (a *App) flushLogs() {
	a.logMu.Lock()
	pend := a.logPend
	a.logPend, a.logArmed = nil, false
	a.logMu.Unlock()
	for len(pend) > 0 {
		n := min(len(pend), logBatch)
		a.emitWait("logs", pend[:n])
		pend = pend[n:]
	}
}
