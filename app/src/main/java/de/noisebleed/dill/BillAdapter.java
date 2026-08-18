package de.noisebleed.dill;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BillAdapter extends RecyclerView.Adapter<BillAdapter.Holder> {

    public interface OnBillClickListener {
        void onBillClick(Bill bill);
    }

    private final List<Bill> bills = new ArrayList<>();
    private final OnBillClickListener listener;

    public BillAdapter(OnBillClickListener listener) {
        this.listener = listener;
    }

    public void setBills(List<Bill> newBills) {
        bills.clear();
        bills.addAll(newBills);
        // Die Liste ist klein, ein komplettes Neuzeichnen ist billiger als DiffUtil-Pflege.
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bill, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Bill bill = bills.get(position);
        holder.bind(bill, listener);
    }

    @Override
    public int getItemCount() {
        return bills.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        private final TextView doctor;
        private final TextView details;
        private final TextView status;

        Holder(View itemView) {
            super(itemView);
            doctor = itemView.findViewById(R.id.bill_doctor);
            details = itemView.findViewById(R.id.bill_details);
            status = itemView.findViewById(R.id.bill_status);
        }

        void bind(Bill bill, OnBillClickListener listener) {
            var context = itemView.getContext();
            doctor.setText(bill.doctor.isEmpty() ? context.getString(R.string.unknown_doctor) : bill.doctor);

            String date = bill.displayBillingDate();
            String amount = bill.displayAmount();
            StringBuilder line = new StringBuilder();
            line.append(date != null ? date : context.getString(R.string.unknown_date));
            line.append("  ·  ");
            line.append(amount != null ? amount : context.getString(R.string.unknown_amount));
            details.setText(line);

            StringBuilder state = new StringBuilder();
            state.append(context.getString(bill.exported ? R.string.status_exported : R.string.status_new));
            if (bill.paid) {
                state.append("  ·  ").append(context.getString(R.string.status_paid));
            }
            if (bill.sentHuk) {
                state.append("  ·  ").append(context.getString(R.string.status_huk));
            }
            if (bill.sentBeihilfe) {
                state.append("  ·  ").append(context.getString(R.string.status_beihilfe));
            }
            status.setText(state);
            status.setTextColor(ContextCompat.getColor(context,
                    bill.exported ? R.color.status_done : R.color.status_new));

            itemView.setOnClickListener(v -> listener.onBillClick(bill));
        }
    }
}
