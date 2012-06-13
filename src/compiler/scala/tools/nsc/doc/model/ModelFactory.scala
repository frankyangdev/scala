/* NSC -- new Scala compiler -- Copyright 2007-2011 LAMP/EPFL */

package scala.tools.nsc
package doc
package model

import comment._

import diagram._

import scala.collection._
import scala.util.matching.Regex

import symtab.Flags

import io._

import model.{ RootPackage => RootPackageEntity }

/** This trait extracts all required information for documentation from compilation units */
class ModelFactory(val global: Global, val settings: doc.Settings) {
  thisFactory: ModelFactory with ModelFactoryImplicitSupport with DiagramFactory with CommentFactory with TreeFactory =>

  import global._
  import definitions.{ ObjectClass, NothingClass, AnyClass, AnyValClass, AnyRefClass }
  import rootMirror.{ RootPackage, RootClass, EmptyPackage }

  def templatesCount = docTemplatesCache.count(_._2.isDocTemplate) - droppedPackages.size

  private var _modelFinished = false
  def modelFinished: Boolean = _modelFinished
  private var universe: Universe = null

  private def dbg(msg: String) = if (sys.props contains "scala.scaladoc.debug") println(msg)
  private def closestPackage(sym: Symbol) = {
    if (sym.isPackage || sym.isPackageClass) sym
    else sym.enclosingPackage
  }

  private def printWithoutPrefix(memberSym: Symbol, templateSym: Symbol) = {
    dbg(
      "memberSym " + memberSym + " templateSym " + templateSym + " encls = " +
      closestPackage(memberSym) + ", " + closestPackage(templateSym)
    )
    memberSym.isOmittablePrefix || (closestPackage(memberSym) == closestPackage(templateSym))
  }

  private lazy val noSubclassCache = Set[Symbol](AnyClass, AnyRefClass, ObjectClass)

  def makeModel: Option[Universe] = {
    val universe = new Universe { thisUniverse =>
      thisFactory.universe = thisUniverse
      val settings = thisFactory.settings
      val rootPackage = modelCreation.createRootPackage
    }
    _modelFinished = true
    // complete the links between model entities, everthing that couldn't have been done before
    universe.rootPackage.completeModel

    Some(universe) filter (_.rootPackage != null)
  }

  // state:
  var ids = 0
  private val droppedPackages = mutable.Set[PackageImpl]()
  protected val docTemplatesCache = new mutable.LinkedHashMap[Symbol, DocTemplateImpl]
  protected val noDocTemplatesCache = new mutable.LinkedHashMap[Symbol, NoDocTemplateImpl]
  protected var typeCache = new mutable.LinkedHashMap[Type, TypeEntity]

  def optimize(str: String): String =
    if (str.length < 16) str.intern else str

  /* ============== IMPLEMENTATION PROVIDING ENTITY TYPES ============== */

  abstract class EntityImpl(val sym: Symbol, val inTpl: TemplateImpl) extends Entity {
    val id = { ids += 1; ids }
    val name = optimize(sym.nameString)
    val universe = thisFactory.universe

    // Debugging:
    // assert(id != 36, sym + "  " + sym.getClass)
    //println("Creating entity #" + id + " [" + kind + " " + qualifiedName + "] for sym " + sym.kindString + " " + sym.ownerChain.reverse.map(_.name).mkString("."))

    def inTemplate: TemplateImpl = inTpl
    def toRoot: List[EntityImpl] = this :: inTpl.toRoot
    def qualifiedName = name
    def annotations = sym.annotations.map(makeAnnotation)
  }

  trait TemplateImpl extends EntityImpl with TemplateEntity {
    override def qualifiedName: String =
      if (inTemplate == null || inTemplate.isRootPackage) name else optimize(inTemplate.qualifiedName + "." + name)
    def isPackage = sym.isPackage
    def isTrait = sym.isTrait
    def isClass = sym.isClass && !sym.isTrait
    def isObject = sym.isModule && !sym.isPackage
    def isCaseClass = sym.isCaseClass
    def isRootPackage = false
    def ownType = makeType(sym.tpe, this)
    def selfType = if (sym.thisSym eq sym) None else Some(makeType(sym.thisSym.typeOfThis, this))
    def inPackageObject: Boolean = sym.owner.isModuleClass && sym.owner.sourceModule.isPackageObject
  }

  abstract class MemberImpl(sym: Symbol, implConv: ImplicitConversionImpl, inTpl: DocTemplateImpl) extends EntityImpl(sym, inTpl) with MemberEntity {
    lazy val comment = if (inTpl != null) thisFactory.comment(sym, inTpl) else None
    override def inTemplate = inTpl
    override def toRoot: List[MemberImpl] = this :: inTpl.toRoot
    def inDefinitionTemplates = this match {
        case mb: NonTemplateMemberEntity if (mb.useCaseOf.isDefined) =>
          mb.useCaseOf.get.inDefinitionTemplates
        case _ =>
          if (inTpl == null)
            List(makeRootPackage)
          else
            makeTemplate(sym.owner)::(sym.allOverriddenSymbols map { inhSym => makeTemplate(inhSym.owner) })
      }
    def visibility = {
      if (sym.isPrivateLocal) PrivateInInstance()
      else if (sym.isProtectedLocal) ProtectedInInstance()
      else {
        val qual =
          if (sym.hasAccessBoundary)
            Some(makeTemplate(sym.privateWithin))
          else None
        if (sym.isPrivate) PrivateInTemplate(inTpl)
        else if (sym.isProtected) ProtectedInTemplate(qual getOrElse inTpl)
        else if (qual.isDefined) PrivateInTemplate(qual.get)
        else Public()
      }
    }
    def flags = {
      val fgs = mutable.ListBuffer.empty[Paragraph]
      if (sym.isImplicit) fgs += Paragraph(Text("implicit"))
      if (sym.isSealed) fgs += Paragraph(Text("sealed"))
      if (!sym.isTrait && (sym hasFlag Flags.ABSTRACT)) fgs += Paragraph(Text("abstract"))
      /* Resetting the DEFERRED flag is a little trick here for refined types: (example from scala.collections)
       * {{{
       *     implicit def traversable2ops[T](t: collection.GenTraversableOnce[T]) = new TraversableOps[T] {
       *       def isParallel = ...
       * }}}
       * the type the method returns is TraversableOps, which has all-abstract symbols. But in reality, it couldn't have
       * any abstract terms, otherwise it would fail compilation. So we reset the DEFERRED flag. */
      if (!sym.isTrait && (sym hasFlag Flags.DEFERRED) && (implConv eq null)) fgs += Paragraph(Text("abstract"))
      if (!sym.isModule && (sym hasFlag Flags.FINAL)) fgs += Paragraph(Text("final"))
      fgs.toList
    }
    def deprecation =
      if (sym.isDeprecated)
        Some((sym.deprecationMessage, sym.deprecationVersion) match {
          case (Some(msg), Some(ver)) => parseWiki("''(Since version " + ver + ")'' " + msg, NoPosition)
          case (Some(msg), None) => parseWiki(msg, NoPosition)
          case (None, Some(ver)) =>  parseWiki("''(Since version " + ver + ")''", NoPosition)
          case (None, None) => Body(Nil)
        })
      else
        comment flatMap { _.deprecated }
    def migration =
      if(sym.hasMigrationAnnotation)
        Some((sym.migrationMessage, sym.migrationVersion) match {
          case (Some(msg), Some(ver)) => parseWiki("''(Changed in version " + ver + ")'' " + msg, NoPosition)
          case (Some(msg), None) => parseWiki(msg, NoPosition)
          case (None, Some(ver)) =>  parseWiki("''(Changed in version " + ver + ")''", NoPosition)
          case (None, None) => Body(Nil)
        })
      else
        None
    def inheritedFrom =
      if (inTemplate.sym == this.sym.owner || inTemplate.sym.isPackage) Nil else
        makeTemplate(this.sym.owner) :: (sym.allOverriddenSymbols map { os => makeTemplate(os.owner) })
    def resultType = {
      def resultTpe(tpe: Type): Type = tpe match { // similar to finalResultType, except that it leaves singleton types alone
        case PolyType(_, res) => resultTpe(res)
        case MethodType(_, res) => resultTpe(res)
        case NullaryMethodType(res) => resultTpe(res)
        case _ => tpe
      }
      val tpe = if (implConv eq null) sym.tpe else implConv.toType memberInfo sym
      makeTypeInTemplateContext(resultTpe(tpe), inTemplate, sym)
    }
    def isDef = false
    def isVal = false
    def isLazyVal = false
    def isVar = false
    def isImplicit = sym.isImplicit
    def isConstructor = false
    def isAliasType = false
    def isAbstractType = false
    def isAbstract =
      // for the explanation of implConv == null see comment on flags
      ((!sym.isTrait && ((sym hasFlag Flags.ABSTRACT) || (sym hasFlag Flags.DEFERRED)) && (implConv == null)) ||
      sym.isAbstractClass || sym.isAbstractType) && !sym.isSynthetic
    def isTemplate = false
    def byConversion = if (implConv ne null) Some(implConv) else None
  }

  /** A template that is not documented at all. The class is instantiated during lookups, to indicate that the class
   *  exists, but should not be documented (either it's not included in the source or it's not visible)
   */
  class NoDocTemplateImpl(sym: Symbol, inTpl: TemplateImpl) extends EntityImpl(sym, inTpl) with TemplateImpl with HigherKindedImpl with NoDocTemplate {
    assert(modelFinished)
    assert(!(noDocTemplatesCache isDefinedAt sym))
    noDocTemplatesCache += (sym -> this)

    def isDocTemplate = false
  }

  /** An inherited template that was not documented in its original owner - example:
   *  in classpath:  trait T { class C } -- T (and implicitly C) are not documented
   *  in the source: trait U extends T -- C appears in U as a NoDocTemplateMemberImpl -- that is, U has a member for it
   *  but C doesn't get its own page
   */
  class NoDocTemplateMemberImpl(sym: Symbol, inTpl: DocTemplateImpl) extends MemberImpl(sym, null, inTpl) with TemplateImpl with HigherKindedImpl with NoDocTemplateMemberEntity {
    assert(modelFinished)

    def isDocTemplate = false
    lazy val definitionName = optimize(inDefinitionTemplates.head.qualifiedName + "." + name)
  }

   /** The instantiation of `TemplateImpl` triggers the creation of the following entities:
    *  All ancestors of the template and all non-package members.
    */
  abstract class DocTemplateImpl(sym: Symbol, inTpl: DocTemplateImpl) extends MemberImpl(sym, null, inTpl) with TemplateImpl with HigherKindedImpl with DocTemplateEntity {
    assert(!modelFinished)
    assert(!(docTemplatesCache isDefinedAt sym), sym)
    docTemplatesCache += (sym -> this)

    if (settings.verbose.value)
      inform("Creating doc template for " + sym)

    override def toRoot: List[DocTemplateImpl] = this :: inTpl.toRoot
    def inSource =
      if (sym.sourceFile != null && ! sym.isSynthetic)
        Some((sym.sourceFile, sym.pos.line))
      else
        None

    def sourceUrl = {
      def fixPath(s: String) = s.replaceAll("\\" + java.io.File.separator, "/")
      val assumedSourceRoot  = fixPath(settings.sourcepath.value) stripSuffix "/"

      if (!settings.docsourceurl.isDefault)
        inSource map { case (file, _) =>
          val filePath = fixPath(file.path).replaceFirst("^" + assumedSourceRoot, "").stripSuffix(".scala")
          val tplOwner = this.inTemplate.qualifiedName
          val tplName = this.name
          val patches = new Regex("""€\{(FILE_PATH|TPL_OWNER|TPL_NAME)\}""")
          def substitute(name: String): String = name match {
            case "FILE_PATH" => filePath
            case "TPL_OWNER" => tplOwner
            case "TPL_NAME" => tplName
          }
          val patchedString = patches.replaceAllIn(settings.docsourceurl.value, m => java.util.regex.Matcher.quoteReplacement(substitute(m.group(1))) )
          new java.net.URL(patchedString)
        }
      else None
    }

    def parentTemplates =
      if (sym.isPackage || sym == AnyClass)
        List()
      else
        sym.tpe.parents.flatMap { tpe: Type =>
          val tSym = tpe.typeSymbol
          if (tSym != NoSymbol)
            List(makeTemplate(tSym))
          else
            List()
        } filter (_.isInstanceOf[DocTemplateEntity])

    def parentTypes =
      if (sym.isPackage || sym == AnyClass) List() else {
        val tps = sym.tpe.parents map { _.asSeenFrom(sym.thisType, sym) }
        makeParentTypes(RefinedType(tps, EmptyScope), inTpl)
      }

    protected def linearizationFromSymbol(symbol: Symbol): List[(TemplateEntity, TypeEntity)] = {
      symbol.ancestors map { ancestor =>
        val typeEntity = makeType(symbol.info.baseType(ancestor), this)
        val tmplEntity = makeTemplate(ancestor) match {
          case tmpl: DocTemplateImpl  => tmpl registerSubClass this ; tmpl
          case tmpl                   => tmpl
        }
        (tmplEntity, typeEntity)
      }
    }

    lazy val linearization = linearizationFromSymbol(sym)
    def linearizationTemplates = linearization map { _._1 }
    def linearizationTypes = linearization map { _._2 }

    /* Subclass cache */
    private lazy val subClassesCache = (
      if (noSubclassCache(sym)) null
      else mutable.ListBuffer[DocTemplateEntity]()
    )
    def registerSubClass(sc: DocTemplateEntity): Unit = {
      if (subClassesCache != null)
        subClassesCache += sc
    }
    def allSubClasses = if (subClassesCache == null) Nil else subClassesCache.toList
    def directSubClasses = allSubClasses.filter(_.parentTypes.map(_._1).contains(this))

    /* Implcitly convertible class cache */
    private var implicitlyConvertibleClassesCache: mutable.ListBuffer[DocTemplateEntity] = null
    def registerImplicitlyConvertibleClass(sc: DocTemplateEntity): Unit = {
      if (implicitlyConvertibleClassesCache == null)
        implicitlyConvertibleClassesCache = mutable.ListBuffer[DocTemplateEntity]()
      implicitlyConvertibleClassesCache += sc
    }

    def incomingImplicitlyConvertedClasses: List[DocTemplateEntity] =
      if (implicitlyConvertibleClassesCache == null)
        List()
      else
        implicitlyConvertibleClassesCache.toList

    // the implicit conversions are generated eagerly, but the members generated by implicit conversions are added
    // lazily, on completeModel
    val conversions: List[ImplicitConversionImpl] =
      if (settings.docImplicits.value) makeImplicitConversions(sym, this) else Nil

    // members as given by the compiler
    lazy val memberSyms      = sym.info.members.filter(s => membersShouldDocument(s, this))

    // the inherited templates (classes, traits or objects)
    var memberSymsLazy  = memberSyms.filter(t => templateShouldDocument(t, this) && !inOriginalOnwer(t, this))
    // the direct members (methods, values, vars, types and directly contained templates)
    var memberSymsEager = memberSyms.filter(!memberSymsLazy.contains(_))
    // the members generated by the symbols in memberSymsEager
    val ownMembers      = (memberSyms.flatMap(makeMember(_, null, this)))

    // all the members that are documentented PLUS the members inherited by implicit conversions
    var members: List[MemberImpl] = ownMembers

    def templates       = members collect { case c: DocTemplateEntity => c }
    def methods         = members collect { case d: Def => d }
    def values          = members collect { case v: Val => v }
    def abstractTypes   = members collect { case t: AbstractType => t }
    def aliasTypes      = members collect { case t: AliasType => t }

    /**
     * This is the final point in the core model creation: no DocTemplates are created after the model has finished, but
     * inherited templates and implicit members are added to the members at this point.
     */
    def completeModel: Unit = {
      // DFS completion
      for (member <- members)
        member match {
          case d: DocTemplateImpl => d.completeModel
          case _ =>
        }

      members :::= memberSymsLazy.map(modelCreation.createLazyTemplateMember(_, inTpl))

      // compute linearization to register subclasses
      linearization
      outgoingImplicitlyConvertedClasses

      // the members generated by the symbols in memberSymsEager PLUS the members from the usecases
      val allMembers = ownMembers ::: ownMembers.flatMap(_.useCaseOf.map(_.asInstanceOf[MemberImpl])).distinct
      implicitsShadowing = makeShadowingTable(allMembers, conversions, this)
      // finally, add the members generated by implicit conversions
      members :::= conversions.flatMap(_.memberImpls)
    }

    var implicitsShadowing = Map[MemberEntity, ImplicitMemberShadowing]()

    lazy val outgoingImplicitlyConvertedClasses: List[(TemplateEntity, TypeEntity)] = conversions flatMap (conv =>
      if (!implicitExcluded(conv.conversionQualifiedName))
        conv.targetTypeComponents map {
          case pair@(template, tpe) =>
            template match {
              case d: DocTemplateImpl => d.registerImplicitlyConvertibleClass(this)
              case _ => // nothing
            }
            pair
        }
      else List()
    )

    override def isTemplate = true
    lazy val definitionName = optimize(inDefinitionTemplates.head.qualifiedName + "." + name)
    def isDocTemplate = true
    def companion = sym.companionSymbol match {
      case NoSymbol => None
      case comSym if !isEmptyJavaObject(comSym) && (comSym.isClass || comSym.isModule) =>
        makeTemplate(comSym) match {
          case d: DocTemplateImpl => Some(d)
          case _ => None
        }
      case _ => None
    }

    // We make the diagram a lazy val, since we're not sure we'll include the diagrams in the page
    lazy val inheritanceDiagram = makeInheritanceDiagram(this)
    lazy val contentDiagram = makeContentDiagram(this)
  }

  abstract class PackageImpl(sym: Symbol, inTpl: PackageImpl) extends DocTemplateImpl(sym, inTpl) with Package {
    override def inTemplate = inTpl
    override def toRoot: List[PackageImpl] = this :: inTpl.toRoot
    override lazy val linearization = {
      val symbol = sym.info.members.find {
        s => s.isPackageObject
      } getOrElse sym
      linearizationFromSymbol(symbol)
    }
    def packages = members collect { case p: PackageImpl if !(droppedPackages contains p) => p }
  }

  abstract class RootPackageImpl(sym: Symbol) extends PackageImpl(sym, null) with RootPackageEntity

  abstract class NonTemplateMemberImpl(sym: Symbol, implConv: ImplicitConversionImpl, inTpl: DocTemplateImpl) extends MemberImpl(sym, implConv, inTpl) with NonTemplateMemberEntity {
    override def qualifiedName = optimize(inTemplate.qualifiedName + "#" + name)
    lazy val definitionName =
      if (implConv == null) optimize(inDefinitionTemplates.head.qualifiedName + "#" + name)
      else                  optimize(implConv.conversionQualifiedName + "#" + name)
    def isUseCase = sym.isSynthetic
    def isBridge = sym.isBridge
  }

  abstract class NonTemplateParamMemberImpl(sym: Symbol, implConv: ImplicitConversionImpl, inTpl: DocTemplateImpl) extends NonTemplateMemberImpl(sym, implConv, inTpl) {
    def valueParams = {
      val info = if (implConv eq null) sym.info else implConv.toType memberInfo sym
      info.paramss map { ps => (ps.zipWithIndex) map { case (p, i) =>
        if (p.nameString contains "$") makeValueParam(p, inTpl, optimize("arg" + i)) else makeValueParam(p, inTpl)
      }}
    }
  }

  abstract class ParameterImpl(val sym: Symbol, val inTpl: TemplateImpl) extends ParameterEntity {
    val name = optimize(sym.nameString)
  }

  private trait TypeBoundsImpl {
    def sym: Symbol
    def inTpl: TemplateImpl
    def lo = sym.info.bounds match {
      case TypeBounds(lo, hi) if lo.typeSymbol != NothingClass =>
        Some(makeTypeInTemplateContext(appliedType(lo, sym.info.typeParams map {_.tpe}), inTpl, sym))
      case _ => None
    }
    def hi = sym.info.bounds match {
      case TypeBounds(lo, hi) if hi.typeSymbol != AnyClass =>
        Some(makeTypeInTemplateContext(appliedType(hi, sym.info.typeParams map {_.tpe}), inTpl, sym))
      case _ => None
    }
  }

  trait HigherKindedImpl extends HigherKinded {
    def sym: Symbol
    def inTpl: TemplateImpl
    def typeParams =
      sym.typeParams map (makeTypeParam(_, inTpl))
  }
  /* ============== MAKER METHODS ============== */

  /** */
  def normalizeTemplate(aSym: Symbol): Symbol = aSym match {
    case null | rootMirror.EmptyPackage | NoSymbol =>
      normalizeTemplate(RootPackage)
    case ObjectClass =>
      normalizeTemplate(AnyRefClass)
    case _ if aSym.isPackageObject =>
      aSym
    case _ if aSym.isModuleClass =>
      normalizeTemplate(aSym.sourceModule)
    case _ =>
      aSym
  }

  /**
   * These are all model construction methods. Please do not use them directly, they are calling each other recursively
   * starting from makeModel. On the other hand, makeTemplate, makeAnnotation, makeMember, makeType should only be used
   * after the model was created (modelFinished=true) otherwise assertions will start failing.
   */
  object modelCreation {

    def createRootPackage: PackageImpl = docTemplatesCache.get(RootPackage) match {
      case Some(root: PackageImpl) => root
      case _ => modelCreation.createTemplate(RootPackage, null).asInstanceOf[PackageImpl]
    }

    /**
     *  Create a template, either a package, class, trait or object
     */
    def createTemplate(aSym: Symbol, inTpl: DocTemplateImpl): DocTemplateImpl = {
      // don't call this after the model finished!
      assert(!modelFinished)

      def createRootPackageComment: Option[Comment] =
        if(settings.docRootContent.isDefault) None
        else {
          import Streamable._
          Path(settings.docRootContent.value) match {
            case f : File => {
              val rootComment = closing(f.inputStream)(is => parse(slurp(is), "", NoPosition))
              Some(rootComment)
            }
            case _ => None
          }
        }

      def createDocTemplate(bSym: Symbol, inTpl: DocTemplateImpl): DocTemplateImpl = {
        if (bSym.isModule || (bSym.isAliasType && bSym.tpe.typeSymbol.isModule))
          new DocTemplateImpl(bSym, inTpl) with Object
        else if (bSym.isTrait || (bSym.isAliasType && bSym.tpe.typeSymbol.isTrait))
          new DocTemplateImpl(bSym, inTpl) with Trait
        else if (bSym.isClass || (bSym.isAliasType && bSym.tpe.typeSymbol.isClass))
          new DocTemplateImpl(bSym, inTpl) with Class {
            def valueParams =
              // we don't want params on a class (non case class) signature
              if (isCaseClass) List(sym.constrParamAccessors map (makeValueParam(_, this)))
              else List.empty
            val constructors =
              members collect { case d: Constructor => d }
            def primaryConstructor = constructors find { _.isPrimary }
          }
        else
          sys.error("'" + bSym + "' isn't a class, trait or object thus cannot be built as a documentable template")
      }

      val bSym = normalizeTemplate(aSym)
      if (docTemplatesCache isDefinedAt bSym)
        return docTemplatesCache(bSym)

      /* Three cases of templates:
       * (1) root package -- special cased for bootstrapping
       * (2) package
       * (3) class/object/trait
       */
      if (bSym == RootPackage) // (1)
        new RootPackageImpl(bSym) {
          override lazy val comment = createRootPackageComment
          override val name = "root"
          override def inTemplate = this
          override def toRoot = this :: Nil
          override def qualifiedName = "_root_"
          override def inheritedFrom = Nil
          override def isRootPackage = true
          override lazy val memberSyms =
            (bSym.info.members ++ EmptyPackage.info.members) filter { s =>
              s != EmptyPackage && s != RootPackage
            }
        }
      else if (bSym.isPackage) // (2)
        inTpl match {
          case inPkg: PackageImpl =>
            val pack = new PackageImpl(bSym, inPkg) {}
            if (pack.templates.isEmpty && pack.memberSymsLazy.isEmpty)
              droppedPackages += pack
            pack
          case _ =>
            sys.error("'" + bSym + "' must be in a package")
        }
      else {
        // no class inheritance at this point
        assert(inOriginalOnwer(bSym, inTpl))
        createDocTemplate(bSym, inTpl)
      }
    }

    /**
     *  After the model is completed, no more DocTemplateEntities are created.
     *  Therefore any symbol that still appears is:
     *   - NoDocTemplateMemberEntity (created here)
     *   - NoDocTemplateEntity (created in makeTemplate)
     */
    def createLazyTemplateMember(aSym: Symbol, inTpl: DocTemplateImpl): MemberImpl = {
      assert(modelFinished)
      val bSym = normalizeTemplate(aSym)

      if (docTemplatesCache isDefinedAt bSym)
        docTemplatesCache(bSym)
      else
        docTemplatesCache.get(bSym.owner) match {
          case Some(inTpl) =>
            val mbrs = inTpl.members.collect({ case mbr: MemberImpl if mbr.sym == bSym => mbr })
            assert(mbrs.length == 1)
            mbrs.head
          case _ =>
            // move the class completely to the new location
            new NoDocTemplateMemberImpl(aSym, inTpl)
        }
    }
  }

  /** Get the root package */
  def makeRootPackage: PackageImpl = docTemplatesCache(RootPackage).asInstanceOf[PackageImpl]

  // TODO: Should be able to override the type
  def makeMember(aSym: Symbol, implConv: ImplicitConversionImpl, inTpl: DocTemplateImpl): List[MemberImpl] = {

    def makeMember0(bSym: Symbol, _useCaseOf: Option[MemberImpl]): Option[MemberImpl] = {
      if (bSym.isGetter && bSym.isLazy)
          Some(new NonTemplateMemberImpl(bSym, implConv, inTpl) with Val {
            override lazy val comment = // The analyser does not duplicate the lazy val's DocDef when it introduces its accessor.
              thisFactory.comment(bSym.accessed, inTpl.asInstanceOf[DocTemplateImpl]) // This hack should be removed after analyser is fixed.
            override def isLazyVal = true
            override def useCaseOf = _useCaseOf
          })
      else if (bSym.isGetter && bSym.accessed.isMutable)
        Some(new NonTemplateMemberImpl(bSym, implConv, inTpl) with Val {
          override def isVar = true
          override def useCaseOf = _useCaseOf
        })
      else if (bSym.isMethod && !bSym.hasAccessorFlag && !bSym.isConstructor && !bSym.isModule) {
        val cSym = { // This unsightly hack closes issue #4086.
          if (bSym == definitions.Object_synchronized) {
            val cSymInfo = (bSym.info: @unchecked) match {
              case PolyType(ts, MethodType(List(bp), mt)) =>
                val cp = bp.cloneSymbol.setPos(bp.pos).setInfo(definitions.byNameType(bp.info))
                PolyType(ts, MethodType(List(cp), mt))
            }
            bSym.cloneSymbol.setPos(bSym.pos).setInfo(cSymInfo)
          }
          else bSym
        }
        Some(new NonTemplateParamMemberImpl(cSym, implConv, inTpl) with HigherKindedImpl with Def {
          override def isDef = true
          override def useCaseOf = _useCaseOf
        })
      }
      else if (bSym.isConstructor && (implConv == null))
        Some(new NonTemplateParamMemberImpl(bSym, implConv, inTpl) with Constructor {
          override def isConstructor = true
          def isPrimary = sym.isPrimaryConstructor
          override def useCaseOf = _useCaseOf
        })
      else if (bSym.isGetter) // Scala field accessor or Java field
        Some(new NonTemplateMemberImpl(bSym, implConv, inTpl) with Val {
          override def isVal = true
          override def useCaseOf = _useCaseOf
        })
      else if (bSym.isAbstractType)
        Some(new NonTemplateMemberImpl(bSym, implConv, inTpl) with TypeBoundsImpl with HigherKindedImpl with AbstractType {
          override def isAbstractType = true
          override def useCaseOf = _useCaseOf
        })
      else if (bSym.isAliasType && bSym != AnyRefClass)
        Some(new NonTemplateMemberImpl(bSym, implConv, inTpl) with HigherKindedImpl with AliasType {
          override def isAliasType = true
          def alias = makeTypeInTemplateContext(sym.tpe.dealias, inTpl, sym)
          override def useCaseOf = _useCaseOf
        })
      else if (bSym.isPackage && !modelFinished)
        inTpl match {
          case inPkg: PackageImpl => modelCreation.createTemplate(bSym, inTpl) match {
            case p: PackageImpl if droppedPackages contains p => None
            case p: PackageImpl => Some(p)
            case _ => sys.error("'" + bSym + "' must be a package")
          }
          case _ =>
            sys.error("'" + bSym + "' must be in a package")
        }
      else if (!modelFinished && templateShouldDocument(bSym, inTpl) && inOriginalOnwer(bSym, inTpl))
        Some(modelCreation.createTemplate(bSym, inTpl))
      else
        None
    }

    if (!localShouldDocument(aSym) || aSym.isModuleClass || aSym.isPackageObject || aSym.isMixinConstructor)
      Nil
    else {
      val allSyms = useCases(aSym, inTpl.sym) map { case (bSym, bComment, bPos) =>
        docComments.put(bSym, DocComment(bComment, bPos)) // put the comment in the list, don't parse it yet, closes SI-4898
        bSym
      }

      val member = makeMember0(aSym, None)
      if (allSyms.isEmpty)
        member.toList
      else
        // Use cases replace the original definitions - SI-5054
        allSyms flatMap { makeMember0(_, member) }
    }
  }

  def findMember(aSym: Symbol, inTpl: DocTemplateImpl): Option[MemberImpl] = {
    val tplSym = normalizeTemplate(aSym.owner)
    inTpl.members.find(_.sym == aSym)
  }

  def findTemplate(query: String): Option[DocTemplateImpl] = {
    assert(modelFinished)
    docTemplatesCache.values find { (tpl: TemplateImpl) => tpl.qualifiedName == query && !tpl.isObject }
  }

  def findTemplateMaybe(aSym: Symbol): Option[DocTemplateImpl] = {
    assert(modelFinished)
    docTemplatesCache.get(normalizeTemplate(aSym))
  }

  def makeTemplate(aSym: Symbol): TemplateImpl = {
    assert(modelFinished)

    def makeNoDocTemplate(aSym: Symbol, inTpl: TemplateImpl): NoDocTemplateImpl = {
      val bSym = normalizeTemplate(aSym)
      noDocTemplatesCache.get(bSym) match {
        case Some(noDocTpl) => noDocTpl
        case None => new NoDocTemplateImpl(bSym, inTpl)
      }
    }

    findTemplateMaybe(aSym) match {
      case Some(dtpl) =>
        dtpl
      case None =>
        val bSym = normalizeTemplate(aSym)
        makeNoDocTemplate(bSym, makeTemplate(bSym.owner))
    }
  }


  /** */
  def makeAnnotation(annot: AnnotationInfo): Annotation = {
    val aSym = annot.symbol
    new EntityImpl(aSym, makeTemplate(aSym.owner)) with Annotation {
      lazy val annotationClass =
        makeTemplate(annot.symbol)
      val arguments = { // lazy
        def noParams = annot.args map { _ => None }
        val params: List[Option[ValueParam]] = annotationClass match {
          case aClass: Class =>
            (aClass.primaryConstructor map { _.valueParams.head }) match {
              case Some(vps) => vps map { Some(_) }
              case None => noParams
            }
          case _ => noParams
        }
        assert(params.length == annot.args.length)
        (params zip annot.args) flatMap { case (param, arg) =>
          makeTree(arg) match {
            case Some(tree) =>
              Some(new ValueArgument {
                def parameter = param
                def value = tree
              })
            case None => None
          }
        }
      }
    }
  }

  /** */
  def makeTypeParam(aSym: Symbol, inTpl: TemplateImpl): TypeParam =
    new ParameterImpl(aSym, inTpl) with TypeBoundsImpl with HigherKindedImpl with TypeParam {
      def variance: String = {
        if (sym hasFlag Flags.COVARIANT) "+"
        else if (sym hasFlag Flags.CONTRAVARIANT) "-"
        else ""
      }
    }

  /** */
  def makeValueParam(aSym: Symbol, inTpl: DocTemplateImpl): ValueParam = {
    makeValueParam(aSym, inTpl, aSym.nameString)
  }


  /** */
  def makeValueParam(aSym: Symbol, inTpl: DocTemplateImpl, newName: String): ValueParam =
    new ParameterImpl(aSym, inTpl) with ValueParam {
      override val name = newName
      def defaultValue =
        if (aSym.hasDefault) {
          // units.filter should return only one element
          (currentRun.units filter (_.source.file == aSym.sourceFile)).toList match {
            case List(unit) =>
              (unit.body find (_.symbol == aSym)) match {
                case Some(ValDef(_,_,_,rhs)) => makeTree(rhs)
                case _ => None
              }
            case _ => None
          }
        }
        else None
      def resultType =
        makeTypeInTemplateContext(aSym.tpe, inTpl, aSym)
      def isImplicit = aSym.isImplicit
    }

  /** */
  def makeTypeInTemplateContext(aType: Type, inTpl: TemplateImpl, dclSym: Symbol): TypeEntity = {
    def ownerTpl(sym: Symbol): Symbol =
      if (sym.isClass || sym.isModule || sym == NoSymbol) sym else ownerTpl(sym.owner)
    val tpe =
      if (thisFactory.settings.useStupidTypes.value) aType else {
        def ownerTpl(sym: Symbol): Symbol =
          if (sym.isClass || sym.isModule || sym == NoSymbol) sym else ownerTpl(sym.owner)
        val fixedSym = if (inTpl.sym.isModule) inTpl.sym.moduleClass else inTpl.sym
        aType.asSeenFrom(fixedSym.thisType, ownerTpl(dclSym))
      }
    makeType(tpe, inTpl)
  }

  /** Get the types of the parents of the current class, ignoring the refinements */
  def makeParentTypes(aType: Type, inTpl: => TemplateImpl): List[(TemplateEntity, TypeEntity)] = aType match {
    case RefinedType(parents, defs) =>
      val ignoreParents = Set[Symbol](AnyClass, ObjectClass)
      val filtParents = parents filterNot (x => ignoreParents(x.typeSymbol))
      filtParents.map(parent => {
        val templateEntity = makeTemplate(parent.typeSymbol)
        val typeEntity = makeType(parent, inTpl)
        (templateEntity, typeEntity)
      })
    case _ =>
      List((makeTemplate(aType.typeSymbol), makeType(aType, inTpl)))
  }

  /** */
  def makeType(aType: Type, inTpl: TemplateImpl): TypeEntity = {
    def templatePackage = closestPackage(inTpl.sym)

    def createTypeEntity = new TypeEntity {
      private val nameBuffer = new StringBuilder
      private var refBuffer = new immutable.TreeMap[Int, (TemplateEntity, Int)]
      private def appendTypes0(types: List[Type], sep: String): Unit = types match {
        case Nil =>
        case tp :: Nil =>
          appendType0(tp)
        case tp :: tps =>
          appendType0(tp)
          nameBuffer append sep
          appendTypes0(tps, sep)
      }

      private def appendType0(tpe: Type): Unit = tpe match {
        /* Type refs */
        case tp: TypeRef if definitions.isFunctionType(tp) =>
          val args = tp.normalize.typeArgs
          nameBuffer append '('
          appendTypes0(args.init, ", ")
          nameBuffer append ") ⇒ "
          appendType0(args.last)
        case tp: TypeRef if definitions.isScalaRepeatedParamType(tp) =>
          appendType0(tp.args.head)
          nameBuffer append '*'
        case tp: TypeRef if definitions.isByNameParamType(tp) =>
          nameBuffer append "⇒ "
          appendType0(tp.args.head)
        case tp: TypeRef if definitions.isTupleType(tp) =>
          val args = tp.normalize.typeArgs
          nameBuffer append '('
          appendTypes0(args, ", ")
          nameBuffer append ')'
        case TypeRef(pre, aSym, targs) =>
          val preSym = pre.widen.typeSymbol
          // There's a work in progress here trying to deal with the
          // places where undesirable prefixes are printed.
          // ...
          // If the prefix is something worthy of printing, see if the prefix type
          // is in the same package as the enclosing template.  If so, print it
          // unqualified and they'll figure it out.
          //
          // val stripPrefixes = List(templatePackage.fullName + ".", "package.", "java.lang.")
          // if (!preSym.printWithoutPrefix) {
          //   nameBuffer append stripPrefixes.foldLeft(pre.prefixString)(_ stripPrefix _)
          // }
          val bSym = normalizeTemplate(aSym)
          if (bSym.isNonClassType) {
            nameBuffer append bSym.decodedName
          } else {
            val tpl = makeTemplate(bSym)
            val pos0 = nameBuffer.length
            refBuffer += pos0 -> (tpl, tpl.name.length)
            nameBuffer append tpl.name
          }
          if (!targs.isEmpty) {
            nameBuffer append '['
            appendTypes0(targs, ", ")
            nameBuffer append ']'
          }
        /* Refined types */
        case RefinedType(parents, defs) =>
          val ignoreParents = Set[Symbol](AnyClass, ObjectClass)
          val filtParents = parents filterNot (x => ignoreParents(x.typeSymbol)) match {
            case Nil    => parents
            case ps     => ps
          }
          appendTypes0(filtParents, " with ")
          // XXX Still todo: properly printing refinements.
          // Since I didn't know how to go about displaying a multi-line type, I went with
          // printing single method refinements (which should be the most common) and printing
          // the number of members if there are more.
          defs.toList match {
            case Nil      => ()
            case x :: Nil => nameBuffer append (" { " + x.defString + " }")
            case xs       => nameBuffer append (" { ... /* %d definitions in type refinement */ }" format xs.size)
          }
        /* Eval-by-name types */
        case NullaryMethodType(result) =>
          nameBuffer append '⇒'
          appendType0(result)
        /* Polymorphic types */
        case PolyType(tparams, result) => assert(tparams.nonEmpty)
//          throw new Error("Polymorphic type '" + tpe + "' cannot be printed as a type")
          def typeParamsToString(tps: List[Symbol]): String = if (tps.isEmpty) "" else
            tps.map{tparam =>
              tparam.varianceString + tparam.name + typeParamsToString(tparam.typeParams)
            }.mkString("[", ", ", "]")
          nameBuffer append typeParamsToString(tparams)
          appendType0(result)
        case tpen =>
          nameBuffer append tpen.toString
      }
      appendType0(aType)
      val refEntity = refBuffer
      val name = optimize(nameBuffer.toString)
    }

    if (aType.isTrivial)
      typeCache.get(aType) match {
        case Some(typeEntity) => typeEntity
        case None =>
          val typeEntity = createTypeEntity
          typeCache += aType -> typeEntity
          typeEntity
      }
    else
      createTypeEntity
  }

  def normalizeOwner(aSym: Symbol): Symbol =
    /*
     * Okay, here's the explanation of what happens. The code:
     *
     * package foo {
     *   object `package` {
     *     class Bar
     *   }
     * }
     *
     * will yield this Symbol structure:
     *
     * +---------------+         +--------------------------+
     * | package foo#1 ----(1)---> module class foo#2       |
     * +---------------+         | +----------------------+ |         +-------------------------+
     *                           | | package object foo#3 ------(1)---> module class package#4  |
     *                           | +----------------------+ |         | +---------------------+ |
     *                           +--------------------------+         | | class package$Bar#5 | |
     *                                                                | +---------------------+ |
     *                                                                +-------------------------+
     * (1) sourceModule
     * (2) you get out of owners with .owner
     */
    normalizeTemplate(aSym) match {
      case bSym if bSym.isPackageObject =>
        normalizeOwner(bSym.owner)
      case bSym =>
        bSym
    }

  def inOriginalOnwer(aSym: Symbol, inTpl: TemplateImpl): Boolean =
    normalizeOwner(aSym.owner) == normalizeOwner(inTpl.sym)

  def templateShouldDocument(aSym: Symbol, inTpl: TemplateImpl): Boolean =
    (aSym.isClass || aSym.isModule || aSym == AnyRefClass) &&
    localShouldDocument(aSym) &&
    !isEmptyJavaObject(aSym) &&
    // either it's inside the original owner or we can document it later:
    (!inOriginalOnwer(aSym, inTpl) || (aSym.isPackageClass || (aSym.sourceFile != null)))

  def membersShouldDocument(sym: Symbol, inTpl: TemplateImpl) =
    // pruning modules that shouldn't be documented
    // Why Symbol.isInitialized? Well, because we need to avoid exploring all the space available to scaladoc
    // from the classpath -- scaladoc is a hog, it will explore everything starting from the root package unless we
    // somehow prune the tree. And isInitialized is a good heuristic for prunning -- if the package was not explored
    // during typer and refchecks, it's not necessary for the current application and there's no need to explore it.
    (!sym.isModule || sym.moduleClass.isInitialized) &&
    // documenting only public and protected members
    localShouldDocument(sym) &&
    // Only this class's constructors are part of its members, inherited constructors are not.
    (!sym.isConstructor || sym.owner == inTpl.sym) &&
    // If the @bridge annotation overrides a normal member, show it
    !isPureBridge(sym)

  def isEmptyJavaObject(aSym: Symbol): Boolean =
    aSym.isModule && aSym.isJavaDefined &&
    aSym.info.members.exists(s => localShouldDocument(s) && (!s.isConstructor || s.owner == aSym))

  def localShouldDocument(aSym: Symbol): Boolean =
    !aSym.isPrivate && (aSym.isProtected || aSym.privateWithin == NoSymbol) && !aSym.isSynthetic

  /** Filter '@bridge' methods only if *they don't override non-bridge methods*. See SI-5373 for details */
  def isPureBridge(sym: Symbol) = sym.isBridge && sym.allOverriddenSymbols.forall(_.isBridge)

  // the classes that are excluded from the index should also be excluded from the diagrams
  def classExcluded(clazz: TemplateEntity): Boolean = settings.hardcoded.isExcluded(clazz.qualifiedName)

  // the implicit conversions that are excluded from the pages should not appear in the diagram
  def implicitExcluded(convertorMethod: String): Boolean = settings.hardcoded.commonConversionTargets.contains(convertorMethod)
}

