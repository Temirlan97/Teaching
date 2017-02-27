package language

/**
 * function checkN checks syntax trees of non-terminal N
 * 
 * every checking method succeeds without output or throws an exception with an error message
 */
object Checker {
  /** checking errors */
  case class Error(msg: String) extends java.lang.Exception(msg)

  // |- context
  def checkContext(context: Context, c: Context): Context = {
    val declsC = checkDeclList(context, c.decls, Nil)
    Context(declsC)
  }
  
  private def checkDeclList(context: Context, decls: List[Decl], sofar: List[Decl]): List[Decl] = {
    decls match {
      case Nil => sofar.reverse
      case hd::tl =>
        val hdC = checkDecl(context, hd)
        checkDeclList(context.and(hdC), tl, hdC::sofar)
    }    
  }
  
  // context |- d
  def checkDecl(context: Context, d: Decl): Decl = {d match {
    case Val(n, tOpt, vOpt) =>
      tOpt.foreach(t => checkType(context, t))
      vOpt match {
        case None =>
          // not allowed by parser, but may only come up locally
          // throw Error("uninitialized variable: " + n.name)
          Val(n, tOpt, vOpt)
        case Some(v) =>
          val (vC,vI) = inferOrCheckType(context, v, tOpt)
          Val(n, Some(vI), Some(vC))
      }
    //********************
    case Command(t) =>
      val (tC,_) = inferOrCheckType(context, t, None)
      Command(tC)
    case Var(x,tOpt,v) =>
      tOpt.foreach(t => checkType(context, t))
      val (vC,vI) = inferOrCheckType(context, v, tOpt)
      Var(x, Some(vI), vC)
    case TypeDecl(_) => d
    case IDTDecl(n, cons) =>
      val asContext = cons.map(c =>
        Val(c.name, Some(FunType(c.argType, TypeRef(n))), None)
      )
      checkContext(context.and(TypeDecl(n)), Context(asContext))
      d
    case ADTDecl(n, fields) =>
      val asContext = fields.map(f =>
        Val(f.name, Some(f.tp), None)
      )
      checkContext(context.and(TypeDecl(n)), Context(asContext))
      d
  }}
  
  // context |- tp : type
  def checkType(context: Context, tp: Type) {tp match {
    case TypeRef(n) =>
      // look up declaration of n in context 
      context.get(n) match {
        case Some(d) => d match {
          // check that n declares a type
          case TypeDecl(_) =>
          case IDTDecl(_,_) =>
          case ADTDecl(_,_) =>
          case _ =>
            throw Error("not a type: " + n.name)
        }
        case None =>
          throw Error("not defined: " + n.name)
      }
    case Int() | Bool() | Unit() =>
      // nothing to do
    case FunType(f,t) =>
      checkType(context, f)
      checkType(context, t)

    //********************
    case LocationType(a) =>
      println("Warning: location types should not occur statically")
      checkType(context, a)
  }}
  
  // tm is well-formed if we can infer tp such that context |- tm : tp
  def checkTerm(context: Context, tm: Term) {
    inferOrCheckType(context, tm, None)
  }
  
  // infers the type tp such that context |- tm : tp
  def inferOrCheckType(context: Context, tm: Term, expected: Option[Type]): (Term,Type) = {
    val (tmC, tmI) = tm match {
      // names
      case TermRef(n) =>
        context.get(n) match {
          case Some(d) => d match {
            // check that n declares a term
            case Val(_, tpO, _) => tpO match {
              case Some(tp) => (tm,tp)
              case None => throw Error("name with unknown type: " + n.name) // should be impossible
            }
            // ***********************
            case Var(_, tpO, _) => tpO match {
              case Some(tp) => (tm,tp)
              case None => throw Error("name with unknown type: " + n.name) // should be impossible
            }
            case _ => throw Error("not a term: " + n.name)
          }
          case None =>
            throw Error("undeclared name: " + n.name)
        }
      
      // base types
      case UnitLit() =>
        (tm,Unit())
      case IntLit(_) =>
        (tm,Int())
      case BoolLit(_) =>
        (tm, Bool())
      case Operator(op, args) =>
        // operator applications behave differently based on the operator
        val argsTypes = args.map(a => inferOrCheckType(context, a, None))
        val (argsC,types) = argsTypes.unzip 
        if (! Operator.builtInInfixOperators.contains(op))
          throw Error("unknown operator")
        val tp = if (args.length == 2) {
          // (in)equality of terms of equal type
          if (types(0) == types(1) && (op == "==" || op == "!=")) {
            Bool()
          } else {
            (types(0),types(1)) match {
              // operators on integers
              case (Int(),Int()) => op match {
                case "+" | "-" | "*" | "mod" | "div" => Int()
                case "<=" | ">=" | ">" | "<" => Bool()
                case _ => throw Error("ill-typed operator application")
              }
              // operators on booleans
              case (Bool(),Bool()) => op match {
                case "&&" | "||" => Bool()
                case _ => throw Error("ill-typed operator application")
              }
              // other cases
              case _ => throw Error("ill-typed operator application")
            }
          }
        } else {
          throw Error("wrong number of arguments for operator " + op)
        }
        (Operator(op,argsC),tp)
      
      // local declarations
      case LocalDecl(d, t) =>
        val dC = checkDecl(context, d)
        val (tC,tI) = inferOrCheckType(context.and(d), t, expected)
        (LocalDecl(dC,tC), tI)

      // function types
      case Lambda(x,tpO,bd) =>
        expected match {
          case Some(FunType(a,b)) =>
            val (bdC, _) = inferOrCheckType(context.and(Val(x, Some(a), None)), bd, Some(b))
            tpO match {
              case Some(tp) => if (a != tp) throw Error("function domain does not match expected type")
              case _ =>
            }
            (Lambda(x, Some(a), bdC), FunType(a, b))
          case _ => tpO match {
            case None => throw Error("cannot infer type of variable in function")
            case Some(tp) =>
              val (bdC, bdType) = inferOrCheckType(context.and(Val(x, Some(tp), None)), bd, None)
              (Lambda(x, Some(tp), bdC), FunType(tp, bdType))
          }
        }
      case Apply(fun,arg) =>
        // turn constructor applications into ConsApply
        fun match {
          case TermRef(n) =>
            context.decls.reverseIterator.exists {
              case d if d.name == n => true
              case IDTDecl(_,cons) if cons.exists(_.name == n) =>
                return inferOrCheckType(context, ConsApply(n, arg), expected)
              case _ => false
            }
          case _ =>
        }
        // now the actual code for this case
        val (funC, funType) = inferOrCheckType(context, fun, None)
        funType match {
          case FunType(from,to) =>
            val (argC, _) = inferOrCheckType(context, arg, Some(from))
            (Apply(funC,argC), to)
          case _ =>
            throw Error("non-function applied to argument")
        }
      
      //********************
      case loc: Location =>
        println("Warning: locations should not occur statically")
        (tm, LocationType(loc.tp))
      case Assignment(x, v) => x match {
        case TermRef(n) => context.get(n) match {
          case Some(Var(_,aO,_)) =>
            val (vC,_) = inferOrCheckType(context,v,aO)
            (Assignment(x,vC),Unit())
          case Some(_) =>
            throw Error("assignment to non-variable")
          case None =>
            throw Error("unknown assignment target: " + n.name)
        }
        case _ =>
          throw Error("assignment to non-name")
      }
      case While(cond, body) =>
        val (condC, _) = inferOrCheckType(context, cond, Some(Bool()))
        val (bodyC, _) = inferOrCheckType(context, body, None)
        (While(condC, bodyC), Unit())
      case Print(tm) =>
        val (tmC,_) = inferOrCheckType(context, tm, None)
        (Print(tmC), Unit())
      
      case ConsApply(con, arg) =>
        Util.mapFind(context.decls.reverse)(d => d match {
          case IDTDecl(a, cs) => cs.find(c => c.name == con) flatMap {
            case Cons(_, argType) =>
              val (argC,_) = inferOrCheckType(context, arg, Some(argType))
              Some((ConsApply(con, argC), TypeRef(a)))
          }
          case _ => None
        }).getOrElse(throw Error("unknown constructor " + con.name))
      case Match(t, cases) =>
        val (tC,tI) = inferOrCheckType(context, t, None)
        tI match {
          case TypeRef(a) =>
            context.get(a) match {
              case Some(IDTDecl(_, cons)) =>
                 var unmatchedCons = cons
                 val (casesC,casesI) = cases.map(cas => cons.find(con => con.name == cas.name) match {
                   case Some(cons) =>
                      unmatchedCons = unmatchedCons diff List(cons)
                      val (bC,bI) = inferOrCheckType(context.and(Val(cas.patvar, Some(cons.argType), None)), cas.body, expected)
                      (ConsCase(cas.name, cas.patvar, Some(cons.argType), bC), bI)
                   case None => throw Error("unknown constructor")
                 }).unzip
                 if (unmatchedCons.nonEmpty)
                   throw Error("match non-exhaustive, missing cases for " + unmatchedCons.map(_.name.name).mkString(", "))
                 val tmC = Match(tC,casesC)
                 expected match {
                   case Some(tpE) =>
                     (tmC,tpE)
                   case None =>
                     if (casesI.distinct.length == 1)
                       (tmC,casesI.head)
                     else
                       throw Error("cases do not agree in type")
                 }
              case Some(_) => throw Error("not an IDT " + a.name)
              case None => throw Error("unknown type " + a.name)
          }
          case _ => throw Error("not an atomic type")
        }
      case New(a, defs) =>
        context.get(a) match {
          case Some(ADTDecl(_,fields)) =>
             var undefinedFields = fields
             val defsC = defs.map(df => fields.find(field => field.name == df.name) match {
               case Some(field) =>
                  undefinedFields = undefinedFields diff List(field)
                  val (fC,_) = inferOrCheckType(context, df.definition, Some(field.tp))
                  FieldDef(field.name, Some(field.tp), fC)
               case None => throw Error("unknown field")
             })
             if (undefinedFields.nonEmpty)
               throw Error("class implementation non-exhaustive, missing definitions for " + undefinedFields.map(_.name.name).mkString(", "))
             (New(a, defsC), TypeRef(a))
          case Some(_) => throw Error("not an ADT " + a.name)
          case None => throw Error("unknown type " + a.name)
        }
      case Instance(a,dfs) =>
        println("Warning: instances should not occur statically")
        (tm, TypeRef(a))
      case FieldAccess(t, field) =>
        val (tC, tI) = inferOrCheckType(context, t, None)
        tI match {
          case TypeRef(a) =>
            context.get(a) match {
              case Some(ADTDecl(_, fs)) => fs.find(f => f.name == field) match {
                 case Some(Field(_, fieldType)) =>
                    (FieldAccess(tC,field),fieldType)
                 case None => throw Error("unknown field")
              }
              case Some(_) => throw Error("not a class type " + a.name)
              case None => throw Error("unknown type " + a.name)
          }
          case _ => throw Error("not an atomic type")
        }
    }
    expected match {
      case Some(tpE) =>
        if (tmI != tpE)
          throw Error("type mismatch: expected " + Printer.printType(tpE) + "; " + "found: " + Printer.printType(tmI))
      case _ =>
    }
    (tmC,tmI)
  }
}

object Util {
  def mapFind[A,B](l: Iterable[A])(f: A => Option[B]): Option[B] =
    if (l.isEmpty) None else f(l.head) orElse mapFind(l.tail)(f)
}