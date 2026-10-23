package ru.mybudget.app.auto
object InsuranceType {
 const val OSAGO="osago"
 const val CASCO="casco"
 val options=listOf(OSAGO,CASCO)
 fun resolve(value:String?)=if(value==CASCO) CASCO else OSAGO
 fun label(value:String?)=if(resolve(value)==CASCO) "КАСКО" else "ОСАГО"
}
