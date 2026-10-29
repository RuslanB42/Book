from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
p=Path(__file__).resolve().parents[1] / 'app/src/main/assets/fonts'
for italic in [False,True]:
 base=TTFont(p/('literata-Literata'+('-Italic' if italic else '')+'[opsz,wght].ttf'))
 for w in [400,600,700]:
  f=instantiateVariableFont(base,{'wght':w,'opsz':18},inplace=False)
  # Static faces avoid variable-axis differences across Android / native test rasterizers.
  f.save(p/f'literata-static-{w}{"-italic" if italic else ""}.ttf')
