; ARKAN CAD PRO - AutoCAD desktop helper sample
; Load with APPLOAD. This is a desktop AutoLISP file, not an Android runtime.

(defun c:ARKANINFO ()
  (princ "\nARKAN CAD PRO - Sewer quantity helper loaded.")
  (princ "\nCommands: ARKANINFO, ARKANLEN")
  (princ)
)

(defun c:ARKANLEN (/ ss i ent data layer total len)
  (setq total 0.0)
  (prompt "\nSelect LINE and LWPOLYLINE entities to total lengths by layer: ")
  (setq ss (ssget '((0 . "LINE,LWPOLYLINE"))))
  (if ss
    (progn
      (setq i 0)
      (while (< i (sslength ss))
        (setq ent (ssname ss i))
        (setq data (entget ent))
        (setq layer (cdr (assoc 8 data)))
        (setq len
          (if (= (cdr (assoc 0 data)) "LINE")
            (distance (cdr (assoc 10 data)) (cdr (assoc 11 data)))
            (vlax-curve-getDistAtParam
              (vlax-ename->vla-object ent)
              (vlax-curve-getEndParam (vlax-ename->vla-object ent))
            )
          )
        )
        (setq total (+ total len))
        (prompt (strcat "\nLayer: " layer " | Length: " (rtos len 2 3)))
        (setq i (1+ i))
      )
      (prompt (strcat "\nTOTAL SELECTED LENGTH: " (rtos total 2 3)))
    )
    (prompt "\nNo supported entities selected.")
  )
  (princ)
)

(princ "\nARKAN_SEWER_TOOLS loaded. Type ARKANINFO or ARKANLEN.")
(princ)
