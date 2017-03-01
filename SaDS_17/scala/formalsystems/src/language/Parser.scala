package language

/** @param input the string to parse
 *  
 *  after creating the parser, call exactly one parsing method depending on the expected concpet
 */
class Parser(input: String) {
   
  /** exception object for parse error */
  case class Error(msg: String) extends java.lang.Exception(msg + " at position " + pos + ", found " + input.substring(pos))
  
  // *********************** basic access of input

  // length of input
  private val length = input.length
  // current position in input - every call to a parseX method parses from this position and increments the position accordingly
  private var pos = 0

  // are we at end of input?
  private def endOfInput = pos == length
  
  // does input start with s at current position?
  private def startsWith(s: String) = pos+s.length <= length && input.substring(pos, pos+s.length) == s
  // does input start with a certain character  
  private def startsWith(is: Char => Boolean) = pos < length && is(input(pos))
  
  // *********************** low-level parsing function that actually inspect the input
  
  // parse a terminal symbol t (and throw it away)
  private def parseTerminal(t: String) = {
     parseWhitespace
     if (startsWith(t)) {
       pos += t.length
     } else {
       throw Error("expected " + t)
     }
     parseWhitespace
  }
  
  // parse and drop initial whitespace (and throw it away)
  private def parseWhitespace {
    while (pos < length && input(pos).isWhitespace && input(pos) != '\n') {
      pos += 1
    }
  }

  // parse some characters (as long as they satisfy a condition) and return them
  private def parseCertainCharacters(take: Char => Boolean): String = {
    parseWhitespace
    val p = pos
    while (pos < length && take(input(pos))) {
       pos += 1
    }
    val n = input.substring(p, pos)
    n
  }
  
  // this defines which characters are allowed in names
  private def parseNameCharacters = parseCertainCharacters(c => c.isLetter)
  
  // e.g., for integer literals 
  private def parseDigits = parseCertainCharacters(c => c.isDigit)
  
  // *********************** parser combinators
  
  // these are operators on parsers that return new parsers
  
  // parses a non-empty list of C's (each one parsed by calling parseC) separated by sep
  private def parseList[C](parseC: () => C, sep: String): List[C] = {
    var cs: List[C] = Nil
    var done = false
    while (!done) {
      val c = parseC()
      cs ::= c
      parseWhitespace
      if (startsWith(sep)) {
        parseTerminal(sep)
      } else {
        done = true
      }
    }
    cs.reverse
  }
  
  // parses an alternative (C1 | C2): tries parseC1 first, if that fails uses parseC2
  private def parseChoice[C](parseC1: () => C, parseC2: () => C): C = {
    val p = pos
    try {
      parseC1()
    } catch {case Error(_) =>
      pos = p // backtrack to original position
      parseC2()
    } 
  }
  
  // parses a C, then applies a function to return a D
  private def map[C,D](parseC: () => C, f: C => D): D = {
    val c = parseC()
    f(c)
  }

  // *********************** high-level parse functions: one function parseN per non-terminal symbol N
  
  def parseContext: Context = {
    parseWhitespace
    val decls = if (endOfInput) {
      Nil
    } else {
      parseList[Decl](() => parseDecl, "\n")
    }
    parseWhitespace
    if (!endOfInput)
      throw Error("expected end of input")
    Context(decls)
  }
   
  def parseDecl: Decl = {
    parseWhitespace
    if (endOfInput)
      throw Error("no declaration found")
    if (startsWith("\n")) {
      // skip empty line
      parseTerminal("\n")
      parseDecl
    } else if (startsWith("val ")) {
      parseTerminal("val")
      parseVal
    }
    // ********************
    else if (startsWith("var ")) {
      parseTerminal("var")
      parseVar
    } else if (startsWith("data ")) {
      parseTerminal("data")
      parseIDT
    } else if (startsWith("class ")) {
      parseTerminal("class")
      parseADT
    } else {
      val tm = parseTerm
      Command(tm)
    }
  }
  
  // auxiliary function of parseDecl
  private def parseVal: Val = {
    val n = parseName
    parseWhitespace
    val tO = if (startsWith(":")) {
       parseTerminal(":")
       Some(parseType)
    } else
      None
    parseTerminal("=")
    val v = parseTerm
    Val(n,tO,Some(v))
  }
   
  // auxiliary function of parseDecl
  private def parseVar: Var = {
    val n = parseName
    parseWhitespace
    val tO = if (startsWith(":")) {
       parseTerminal(":")
       Some(parseType)
    } else
      None
    parseTerminal("=")
    val v = parseTerm
    Var(n,tO,v)
  }
  
  private def parseIDT: IDTDecl = {
    val n = parseName
    parseTerminal("{")
    val cons = parseList(() => parseCons, "|")
    parseTerminal("}")
    IDTDecl(n, cons)
  }
  private def parseCons: Cons = {
    val n = parseName
    parseTerminal("(")
    val t = parseType
    parseTerminal(")")
    Cons(n,t)
  }
  private def parseCase: ConsCase = {
    val n = parseName
    parseTerminal("(")
    val x = parseName
    parseTerminal(")")
    parseTerminal("=>")
    val b = parseTerm
    ConsCase(n,x,None,b)
  }
  
  private def parseADT: ADTDecl = {
    val n = parseName
    parseTerminal("{")
    val fields = parseList(() => parseField, ",")
    parseTerminal("}")
    ADTDecl(n, fields)
  }
  private def parseField: Field = {
    val n = parseName
    parseTerminal(":")
    val t = parseType
    Field(n,t)
  }
  private def parseDef: FieldDef = {
    val n = parseName
    parseTerminal("=")
    val t = parseTerm
    FieldDef(n,None,t)
  }
  
  def parseType: Type = {
    parseWhitespace
    if (endOfInput)
      throw Error("no type found")
    val tp = if (startsWith("(")) {
      // bracketed type
      parseTerminal("(")
      val tp = parseType
      parseTerminal(")")
      tp
    } else {
      // name
      val n = parseNameCharacters
      // some names are predefined, everything else is a TypeRef
      n match {
        case "void" => Void()
        case "unit" => Unit()
        case "int" => Int()
        case "bool" => Bool()
        case n =>
          val name = Name(n)
          TypeRef(name)
      }
    }
    // we have a type now; we check what comes next to decide if we should parse more
    //TODO for product types: handle infix operator *
    val infixOps = List("->")
    parseWhitespace
    // check if an infix operator follows 
    infixOps.find(startsWith) match {
      case Some(op) =>
        // infix operator follows: continue
        parseTerminal(op)
        val second = parseType
        op match {
          case "->" => FunType(tp,second)
        }
      case None =>
        // something else follows: done
        tp
    }
  }

  def parseTerm: Term = {
    parseWhitespace
    if (endOfInput)
      throw Error("no term found")
    val c = input(pos)
    var tm = if (c == '(') {
      parseTerminal("(")
      if (startsWith(")")) {
        parseTerminal(")")
        UnitLit()
      } else {
        // bracketed term
        val tm = parseTerm
        // TODO check for , here to parse pairs (t,t)
        parseTerminal(")")
        tm
      }
    } else if (c == '-' || c.isDigit) {
      // integer literal
      if (c == '-')
        parseTerminal("-")
      val i = parseDigits.toInt
      if (c == '-') IntLit(-i) else IntLit(i)
    } else if (c == '"') {
      // string literal
      ??? //TODO for strings
    } else if (c == '{') {
      // local declaration
      parseTerminal("{")
      val d = parseDecl
      parseTerminal(";")
      val t = parseTerm
      parseTerminal("}")
      LocalDecl(d, t)
    } else if (c == '!') {
      parseTerminal("!")
      val t = parseTerm
      Operator("!", List(t))
    } else {
      // name, some names are predefined or special, everything else is a TermRef
      val n = parseNameCharacters
      n match {
        case "true" =>
          BoolLit(true)
        case "false" =>
          BoolLit(false)
        case "if" =>
          parseTerminal("(")
          val c = parseTerm
          parseTerminal(")")
          val t = parseTerm
          parseTerminal("else")
          val e = parseTerm
          If(c,t,e)
        case "while" =>
          parseTerminal("(")
          val cond = parseTerm
          parseTerminal(")")
          val body = parseTerm
          While(cond, body)
        case "print" =>
          parseTerminal("(")
          val t = parseTerm
          parseTerminal(")")
          Print(t)
        case "new" =>
          val a = parseName
          parseTerminal("{")
          val defs = parseList(() => parseDef, ",")
          parseTerminal("}")
          New(a, defs)
        case "match" =>
          val t = parseTerm
          parseTerminal("{")
          val cases = parseList(() => parseCase, "|")
          parseTerminal("}")
          Match(t, cases)
        case "break" => Break()
        case "continue" => Continue()
        case "return" =>
          val r = parseTerm
          Return(r)
        case "throw" =>
          val e = parseTerm
          Throw(e)
        case "try" =>
          val t = parseTerm
          parseTerminal("catch")
          val h = parseTerm
          Try(t,h)
        case _ =>
          TermRef(Name(n))
      }
    }
    // we have a term now; we check what comes next to decide if we should parse more 
    parseWhitespace
    while (startsWith(".")) {
      // projections out of tm or field access
      parseTerminal(".")
      if (startsWith(c => c.isDigit)) {
    	  val n = parseDigits
    		??? //TODO for product types
      } else {
        val n = parseName
        tm = FieldAccess(tm, n)
      }
    }
    parseWhitespace
    if (startsWith("(")) {
      // function application of tm
      parseTerminal("(")
      val arg = parseTerm
      parseTerminal(")")
      Apply(tm, arg)
    } else if (startsWith("[")) {
      // TODO for type arguments
      ???
    } else if (startsWith(":") || startsWith("=>")) {
      // lambda abstraction, but only if tm is a variable
      tm match {
        case TermRef(x) =>
          val tpO = if (startsWith(":")) {
             parseTerminal(":")
             Some(parseType)
          } else
            None
          parseTerminal("=>")
          val body = parseTerm
          Lambda(x,tpO,body)
        case _ =>
          tm
      }
    } else {
      // check if an infix operator follows
      Operator.builtInInfixOperators.find(startsWith) match {
        case Some(op) =>
          // infix operator
          parseTerminal(op)
          val second = parseTerm
          Operator(op, List(tm, second))
        case None =>
          // ***************** check for an assignment (must come after operators because some operators start with =)
          if (startsWith("=")) {
             parseTerminal("=")
             val second = parseTerm
             Assignment(tm, second)
          } else {
            // done
            tm
          }
      }
    }
  }
  
  def parseName: Name = {
    val n = parseNameCharacters
    if (n == "")
      throw Error("empty name")
    Name(n)
  }
  

  /* not needed, but might come in handy later
  // parses [A_1, ..., A_n], can h
  private def parseTypeArgs: List[Type] = {
    if (startsWith("[")) {
      parseTerminal("[")
      parseWhitespace
      if (startsWith("]")) {
        Nil
      } else {
        val tps = parseList[Type](() => parseType, ",")
        parseTerminal("]")
        tps
      }
    } else {
      Nil
    }
  }
  
  // parses [a_1, ..., a_n]
  private def parseTypeVars: List[Name] = {
    if (startsWith("[")) {
      parseTerminal("[")
      parseWhitespace
      if (startsWith("]")) {
        Nil
      } else {
        val vs = parseList[Name](() => parseName, ",")
        parseTerminal("]")
        vs
      }
    } else {
      Nil
    }
  }
  */
}